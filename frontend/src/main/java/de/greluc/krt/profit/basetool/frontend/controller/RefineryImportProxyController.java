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
import org.jetbrains.annotations.Nullable;
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
 * Uploads a {@code RefineryExtract} JSON, relays it to the backend's {@code POST
 * /api/v1/refinery-orders/import-extract}, and flashes the returned draft into the create form.
 *
 * <p>On success it flashes {@code refineryOrderForm}, {@code importIssues}, {@code importRowIssues}
 * and the {@code importGoodsMatched} / {@code importGoodsTotal} / {@code importRowsSkipped}
 * counters; on failure {@code importErrorKey} or {@code importErrorText}.
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
   * Mapper used only to check that an upload parses as a JSON object; shared with {@code
   * RefineryOrderPageController} through {@link #parseExtractObject}.
   */
  private static final ObjectMapper EXTRACT_MAPPER = JsonMapper.builder().build();

  /**
   * Handles the create-page upload: checks the file parses as JSON, relays it to the backend
   * matcher and redirects to the create page with the pre-filled form; persists nothing.
   *
   * @param file the uploaded {@code RefineryExtract} JSON
   * @param redirectAttributes flash sink for the pre-fill and review attributes
   * @return redirect to {@code /refinery-orders/create}
   */
  @NotNull
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
   * Turns a {@link MaxUploadSizeExceededException} into the same inline invalid-file error as the
   * controller's own size check (REQ-REFINERY-016).
   *
   * @param redirectAttributes flash sink for the error key
   * @return redirect to {@code /refinery-orders/create}
   */
  @NotNull
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public String handleOversizedUpload(@NotNull RedirectAttributes redirectAttributes) {
    redirectAttributes.addFlashAttribute("importErrorKey", "refineryImport.error.invalidFile");
    return "redirect:/refinery-orders/create";
  }

  /**
   * Parses an uploaded {@code RefineryExtract}; shared by {@link #importExtract} and the AJAX twin
   * in {@code RefineryOrderPageController}.
   *
   * @param file the uploaded multipart file
   * @return the parsed JSON object, or {@code null} when the upload is missing, larger than {@link
   *     #MAX_EXTRACT_BYTES}, not JSON or not a JSON object
   */
  @Nullable
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
   * Maps the backend draft into the create form like the detail view's edit form.
   *
   * <p>{@code startedAt} carries the last screenshot's capture time (REQ-REFINERY-017) or stays
   * empty; an empty goods draft keeps one empty seeded row.
   *
   * @param order the draft order
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
   * Groups the findings anchored to a form row ({@code goods[<draftIndex>].<sub>}) by that index.
   *
   * <p>Keys are strings because the Redis session serializer ({@link
   * de.greluc.krt.profit.basetool.frontend.config.RedisSessionConfig}) stringifies flashed map keys
   * (REQ-REFINERY-015).
   *
   * @param issues all draft findings; may be {@code null}
   * @return row findings keyed by the decimal goods index, in encounter order
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
   * Extracts the draft goods index from an issue's field path.
   *
   * @param issue the finding
   * @return the row index, or {@code null} when the issue is not anchored to a row or the index
   *     overflows {@link Integer}
   */
  @Nullable
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
