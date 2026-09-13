/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryGoodForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upload seam of the refinery screenshot import (#435, epic #439): accepts the {@code
 * RefineryExtract} JSON the desktop extractor wrote, relays it as an {@code application/json} body
 * to the backend's {@code POST /api/v1/refinery-orders/import-extract} (Phase 1, #434), and pours
 * the returned non-persisted draft into the <em>existing</em> create form via flash attributes —
 * the {@code GET /refinery-orders/create} handler already prefers a flashed {@code
 * refineryOrderForm} over a fresh one, so the pre-fill needs no new client-side JS.
 *
 * <p>Flash attributes produced on success: {@code refineryOrderForm} (the pre-filled form), {@code
 * importIssues} (order-level + skipped-row findings for the banner), {@code importRowIssues}
 * (draft-row findings keyed by goods index for inline flags) and the {@code importGoodsMatched} /
 * {@code importGoodsTotal} / {@code importRowsSkipped} counters. On failure: {@code importErrorKey}
 * (frontend i18n key, e.g. not-a-JSON upload) or {@code importErrorText} (the backend's
 * already-localized problem detail, e.g. unsupported schema version).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/refinery-orders")
@RequiredArgsConstructor
@Slf4j
public class RefineryImportProxyController {

  /**
   * Sanity cap for the uploaded extract. A real {@code RefineryExtract} is a few KB; anything in
   * the megabyte range is the wrong file. Far below the 72 MB multipart cap on purpose.
   */
  static final long MAX_EXTRACT_BYTES = 2L * 1024 * 1024;

  /** Matches draft-row issue paths ({@code goods[<draftIndex>].<subField>}) for inline anchors. */
  private static final Pattern DRAFT_ROW_FIELD = Pattern.compile("^goods\\[(\\d+)]\\..+$");

  private final BackendApiClient backendApiClient;

  /**
   * Dedicated mapper used only to verify the upload parses as a JSON object before the relay. A
   * {@code static} instance (Jackson {@link ObjectMapper} reads are thread-safe) — not auto-wired,
   * because the server-rendered frontend context runs without a shared {@code ObjectMapper} bean
   * (same rationale as {@code BackendApiClient}); {@code static} so the AJAX import twin in {@code
   * RefineryOrderPageController} can share the parse via {@link #parseExtractObject}.
   */
  private static final ObjectMapper EXTRACT_MAPPER = JsonMapper.builder().build();

  /**
   * Handles the create-page upload form: validates the file is parseable JSON (cheap local check
   * with a friendly message before any backend round-trip), relays it to the Phase 1 matching
   * endpoint, and redirects back to the create page with the pre-filled form plus review flags.
   * Envelope-level backend rejects (wrong {@code schemaVersion}, non-SETUP panel) surface their
   * localized problem detail verbatim; nothing is ever persisted by this flow.
   *
   * @param file the uploaded {@code RefineryExtract} JSON
   * @param redirectAttributes flash sink for the pre-fill and review attributes
   * @return redirect to {@code /refinery-orders/create}
   */
  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("isAuthenticated()")
  public String importExtract(
      @RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes) {
    JsonNode extract = parseExtractObject(file);
    if (extract == null) {
      redirectAttributes.addFlashAttribute("importErrorKey", "refineryImport.error.invalidFile");
      return "redirect:/refinery-orders/create";
    }
    try {
      RefineryImportDraftDto draft =
          backendApiClient.post(
              "/api/v1/refinery-orders/import-extract", extract, RefineryImportDraftDto.class);
      if (draft == null || draft.order() == null) {
        redirectAttributes.addFlashAttribute("importErrorKey", "refineryImport.error.failed");
        return "redirect:/refinery-orders/create";
      }
      redirectAttributes.addFlashAttribute("refineryOrderForm", toForm(draft.order()));
      redirectAttributes.addFlashAttribute("importIssues", generalIssues(draft.issues()));
      redirectAttributes.addFlashAttribute("importRowIssues", rowIssues(draft.issues()));
      redirectAttributes.addFlashAttribute("importGoodsMatched", draft.goodsMatched());
      redirectAttributes.addFlashAttribute("importGoodsTotal", draft.goodsTotal());
      redirectAttributes.addFlashAttribute("importRowsSkipped", draft.rowsSkipped());
      return "redirect:/refinery-orders/create";
    } catch (BackendServiceException e) {
      // Envelope-level reject (e.g. unsupported schemaVersion / panel type): the backend's
      // problem detail is already localized — show it verbatim instead of a generic failure.
      String detail = e.getProblemDetail();
      if (detail != null && !detail.isBlank()) {
        redirectAttributes.addFlashAttribute("importErrorText", detail);
      } else {
        redirectAttributes.addFlashAttribute("importErrorKey", "refineryImport.error.failed");
      }
      return "redirect:/refinery-orders/create";
    } catch (Exception e) {
      log.error("Refinery import relay failed", e);
      redirectAttributes.addFlashAttribute("importErrorKey", "refineryImport.error.failed");
      return "redirect:/refinery-orders/create";
    }
  }

  /**
   * Turns a multipart upload above Spring's 64 MB cap into the same friendly inline error as the
   * controller's own 2 MB sanity check. Without this, picking a grossly wrong file (e.g. the
   * screenshots themselves instead of the extract) raises {@link MaxUploadSizeExceededException}
   * before the handler runs and bounces the user onto the generic 500 error page instead of back to
   * the create form (REQ-REFINERY-016).
   *
   * @param redirectAttributes flash sink for the error key
   * @return redirect to {@code /refinery-orders/create}
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public String handleOversizedUpload(RedirectAttributes redirectAttributes) {
    redirectAttributes.addFlashAttribute("importErrorKey", "refineryImport.error.invalidFile");
    return "redirect:/refinery-orders/create";
  }

  /**
   * Parses and validates an uploaded {@code RefineryExtract}: a missing / empty / oversized upload
   * (above {@link #MAX_EXTRACT_BYTES}), unparseable JSON, or a non-object payload all yield {@code
   * null} (the callers map every such case to the same {@code refineryImport.error.invalidFile}
   * message). Shared by the classic {@link #importExtract} and the AJAX import twin in {@code
   * RefineryOrderPageController} so both apply one parse contract.
   *
   * @param file the uploaded multipart file
   * @return the parsed JSON object node, or {@code null} when the upload is missing, too large, not
   *     JSON, or not a JSON object
   */
  static JsonNode parseExtractObject(MultipartFile file) {
    if (file == null || file.isEmpty() || file.getSize() > MAX_EXTRACT_BYTES) {
      return null;
    }
    try {
      JsonNode extract = EXTRACT_MAPPER.readTree(file.getBytes());
      return extract != null && extract.isObject() ? extract : null;
    } catch (IOException | JacksonException e) {
      log.warn("Refinery import upload was not parseable JSON: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Pours the backend draft into the create form, mirroring the field mapping the detail view
   * already uses for its edit form: ids out of the nested DTOs, the minute total split into the
   * hours/minutes inputs, money fields defaulted to {@code 0} (the form's convention). {@code
   * startedAt} carries the backend-derived capture time of the order's last screenshot as a UTC ISO
   * instant the datetime splitter renders in browser-local time (REQ-REFINERY-017); when the
   * extract carried no capture metadata it stays empty and the create flow defaults it to "now" at
   * save time. An empty goods draft keeps the form's single seeded empty row so the template's
   * row-clone JS keeps working.
   *
   * @param order the draft order (never {@code null})
   * @return the pre-filled form
   */
  static @NotNull RefineryOrderForm toForm(@NotNull RefineryOrderDto order) {
    RefineryOrderForm form = new RefineryOrderForm();
    if (order.owner() != null) {
      form.setOwnerId(order.owner().id());
    }
    if (order.startedAt() != null) {
      form.setStartedAt(order.startedAt().toString());
    }
    if (order.location() != null) {
      form.setLocationId(order.location().id());
    }
    if (order.refiningMethod() != null) {
      form.setRefiningMethodId(order.refiningMethod().id());
    }
    if (order.durationMinutes() != null) {
      form.setDurationHours((int) (order.durationMinutes() / 60));
      form.setDurationMinutes((int) (order.durationMinutes() % 60));
    }
    form.setExpenses(order.expenses() != null ? order.expenses() : 0d);
    form.setStatus(order.status() != null ? order.status() : RefineryOrderStatus.OPEN);
    if (order.goods() != null && !order.goods().isEmpty()) {
      List<RefineryGoodForm> goods = new ArrayList<>();
      for (RefineryGoodDto good : order.goods()) {
        RefineryGoodForm row = new RefineryGoodForm();
        if (good.inputMaterial() != null) {
          row.setInputMaterialId(good.inputMaterial().id());
        }
        if (good.outputMaterial() != null) {
          row.setOutputMaterialId(good.outputMaterial().id());
        }
        row.setInputQuantity(good.inputQuantity());
        row.setOutputQuantity(good.outputQuantity());
        row.setQuality(good.quality());
        goods.add(row);
      }
      form.setGoods(goods);
    }
    return form;
  }

  /**
   * Selects the findings that anchor to a rendered form row ({@code goods[<draftIndex>].<sub>}) and
   * groups them by that index so the template can flag each row inline.
   *
   * <p>The map is keyed by the <em>String</em> form of the index on purpose: flash attributes
   * survive the redirect through the Redis-backed session, and the JSON session serializer ({@link
   * de.greluc.krt.profit.basetool.frontend.config.RedisSessionConfig}) stringifies map keys — an
   * {@code Integer}-keyed map comes back {@code String}-keyed on the next request, making every
   * {@code containsKey(int)} lookup miss silently. The template therefore matches with {@code '' +
   * stat.index} (covers REQ-REFINERY-015).
   *
   * @param issues all draft findings; {@code null}-safe
   * @return draft-row findings keyed by the goods index's decimal string, in encounter order
   */
  static @NotNull Map<String, List<ImportIssueDto>> rowIssues(List<ImportIssueDto> issues) {
    Map<String, List<ImportIssueDto>> byRow = new LinkedHashMap<>();
    if (issues == null) {
      return byRow;
    }
    for (ImportIssueDto issue : issues) {
      Integer row = draftRowIndex(issue);
      if (row != null) {
        byRow.computeIfAbsent(String.valueOf(row), k -> new ArrayList<>()).add(issue);
      }
    }
    return byRow;
  }

  /**
   * Selects the findings without a draft-row anchor (order-level fields plus skipped/un-quoted
   * source rows) for the summary banner.
   *
   * @param issues all draft findings; {@code null}-safe
   * @return banner findings in encounter order
   */
  static @NotNull List<ImportIssueDto> generalIssues(List<ImportIssueDto> issues) {
    List<ImportIssueDto> general = new ArrayList<>();
    if (issues == null) {
      return general;
    }
    for (ImportIssueDto issue : issues) {
      if (draftRowIndex(issue) == null) {
        general.add(issue);
      }
    }
    return general;
  }

  /**
   * Extracts the draft goods index from an issue's field path, or {@code null} when the issue is
   * not anchored to a rendered row (order-level fields, bare {@code goods[n]} skip references). An
   * index beyond {@link Integer} range (the {@code \d+} pattern accepts any digit count) is treated
   * as un-anchored instead of letting the {@link NumberFormatException} abort the whole import.
   *
   * @param issue the finding
   * @return the draft row index, or {@code null}
   */
  private static Integer draftRowIndex(ImportIssueDto issue) {
    if (issue == null || issue.field() == null) {
      return null;
    }
    Matcher matcher = DRAFT_ROW_FIELD.matcher(issue.field());
    if (!matcher.matches()) {
      return null;
    }
    try {
      return Integer.valueOf(matcher.group(1));
    } catch (NumberFormatException e) {
      log.warn("Refinery import issue field index out of int range: {}", issue.field());
      return null;
    }
  }
}
