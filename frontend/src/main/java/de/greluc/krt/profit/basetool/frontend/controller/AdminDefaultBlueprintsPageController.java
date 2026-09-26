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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintProductDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.DefaultBlueprintAddResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.DefaultBlueprintAddSelectionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.DefaultBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.DefaultBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin page for curating the auto-granted default-blueprint set (REQ-INV-017): renders the set,
 * proxies the product type-ahead and relays add and remove to the backend via {@link
 * BackendApiClient}.
 *
 * <p>Mutations run in place through JSON twins (REQ-FE-001); the POST-redirect handlers are the
 * no-JS fallback.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/default-blueprints")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminDefaultBlueprintsPageController {

  /**
   * Character budget for the type-ahead term when it is written to a log line. A product name an
   * admin could plausibly be typing fits comfortably; anything longer is a paste or an attack and
   * is truncated by {@link LogSafe#text(String, int)} rather than allowed to stretch the line.
   */
  private static final int MAX_LOGGED_QUERY = 80;

  /** Backend admin API the page relays its reads and writes to. */
  private static final String BACKEND_BASE = "/api/v1/admin/default-blueprints";

  /**
   * Response type for the blueprint product type-ahead search results. A shared static {@link
   * ParameterizedTypeReference} is behaviourally identical to a fresh anonymous instance per call
   * (Q10).
   */
  private static final ParameterizedTypeReference<List<BlueprintProductDto>>
      BLUEPRINT_PRODUCT_LIST_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for the current default-blueprint set read. */
  private static final ParameterizedTypeReference<List<DefaultBlueprintDto>>
      DEFAULT_BLUEPRINT_LIST_TYPE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the default-blueprint set with the add bar.
   *
   * @param model Thymeleaf model populated with the current default set
   * @return the {@code admin/default-blueprints} view name
   */
  @NotNull
  @GetMapping
  public String view(@NotNull Model model) {
    model.addAttribute("defaults", fetchDefaults());
    return "admin/default-blueprints";
  }

  /**
   * Renders only the list of current defaults for the in-place refresh (REQ-FE-001). Unlike {@link
   * #view}, a backend failure is re-thrown so the client keeps the list on screen and shows a
   * toast.
   *
   * @param model Thymeleaf model populated with the current default set
   * @return the {@code rows} fragment of {@code admin/default-blueprints}
   */
  @NotNull
  @GetMapping(params = "fragment=rows")
  public String rows(@NotNull Model model) {
    List<DefaultBlueprintDto> defaults =
        backendApiClient.get(BACKEND_BASE, DEFAULT_BLUEPRINT_LIST_TYPE);
    model.addAttribute("defaults", defaults == null ? List.of() : defaults);
    return "admin/default-blueprints :: rows";
  }

  /**
   * Type-ahead proxy for the add bar, relaying to the backend blueprint product search.
   *
   * @param q optional case-insensitive product-name substring
   * @param limit optional result cap; defaults to 25, clamped to {@code [1, 200]}
   * @return the matching products, or an empty list on any backend failure
   */
  @GetMapping("/search")
  @ResponseBody
  public List<BlueprintProductDto> search(
      @RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
    try {
      String query = q == null ? "" : q;
      int effectiveLimit = limit == null ? 25 : Math.min(200, Math.max(1, limit));
      String uri = "/api/v1/blueprints/products/search?q={q}&limit=" + effectiveLimit;
      List<BlueprintProductDto> result =
          backendApiClient.get(uri, BLUEPRINT_PRODUCT_LIST_TYPE, query);
      return result == null ? Collections.emptyList() : result;
    } catch (Exception e) {
      log.debug(
          "Default-blueprint product type-ahead failed for query='{}': {}",
          LogSafe.text(q, MAX_LOGGED_QUERY),
          e.getMessage());
      return Collections.emptyList();
    }
  }

  /**
   * No-JS fallback of {@link #addAjax}: adds the staged products and redirects back with a flash
   * toast.
   *
   * @param productKeys the normalized product keys staged by the admin
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the default-blueprints page
   */
  @NotNull
  @PostMapping("/add")
  public String add(
      @RequestParam(name = "productKeys", required = false) List<String> productKeys,
      RedirectAttributes redirectAttributes) {
    DefaultBlueprintAddResultDto result = addAll(productKeys);
    if (!result.failedKeys().isEmpty()) {
      redirectAttributes.addFlashAttribute("errorToast", "admin.defaultBlueprints.error.add");
    } else if (result.added() > 0) {
      redirectAttributes.addFlashAttribute("successToast", "admin.defaultBlueprints.toast.added");
    } else {
      redirectAttributes.addFlashAttribute(
          "successToast", "admin.defaultBlueprints.toast.noneAdded");
    }
    return "redirect:/admin/default-blueprints";
  }

  /**
   * In-place twin of {@link #add}: adds the staged products and returns the per-key outcome. Always
   * {@code 200}, since earlier successful keys are not undone by a later failure.
   *
   * @param request the staged product keys; a {@code null} body or list adds nothing
   * @return {@code 200} with the added and skipped counts and the failed keys
   */
  @ResponseBody
  @PostMapping(value = "/add", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<DefaultBlueprintAddResultDto> addAjax(
      @RequestBody(required = false) @Nullable DefaultBlueprintAddSelectionRequest request) {
    return ResponseEntity.ok(addAll(request == null ? null : request.productKeys()));
  }

  /**
   * No-JS fallback of {@link #removeAjax}: removes a product from the default set and redirects
   * back with a flash toast. Already-granted blueprints stay with their users.
   *
   * @param id default-blueprint entry id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the default-blueprints page
   */
  @NotNull
  @PostMapping("/{id}/delete")
  public String remove(@PathVariable String id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete(
          BACKEND_BASE + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8), Void.class);
      redirectAttributes.addFlashAttribute("successToast", "admin.defaultBlueprints.toast.removed");
    } catch (Exception e) {
      log.error("Failed to remove default blueprint {}", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "admin.defaultBlueprints.error.remove");
    }
    return "redirect:/admin/default-blueprints";
  }

  /**
   * In-place twin of {@link #remove}; a backend failure is relayed as {@code
   * application/problem+json}. Already-granted blueprints stay with their users.
   *
   * @param id default-blueprint entry id; a malformed one yields {@code 400}
   * @return {@code 200} on success, the relayed backend status on failure, {@code 500} on an
   *     unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> removeAjax(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "remove default blueprint " + id + " (ajax)",
        () -> {
          backendApiClient.delete(BACKEND_BASE + "/" + id, Void.class);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Adds each staged key via the backend, shared by both add handlers. Blank keys are dropped, a
   * backend {@code 409} counts as skipped, and other failures are recorded per key without stopping
   * the loop.
   *
   * @param productKeys the staged keys, possibly {@code null}
   * @return the per-key outcome
   */
  @NotNull
  private DefaultBlueprintAddResultDto addAll(@Nullable List<String> productKeys) {
    List<String> keys = productKeys == null ? List.of() : productKeys;
    int added = 0;
    int skipped = 0;
    List<String> failedKeys = new ArrayList<>();
    for (String raw : keys) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String key = raw.trim();
      try {
        backendApiClient.post(
            BACKEND_BASE, new DefaultBlueprintCreateRequest(key), DefaultBlueprintDto.class);
        added++;
      } catch (BackendServiceException e) {
        if (e.getStatusCode() == HttpStatus.CONFLICT.value()) {
          skipped++;
          continue;
        }
        log.debug(
            "Failed to add default blueprint '{}': {}",
            LogSafe.text(key, MAX_LOGGED_QUERY),
            e.getMessage());
        failedKeys.add(key);
      } catch (Exception e) {
        log.error("Failed to add default blueprint '{}'", LogSafe.text(key, MAX_LOGGED_QUERY), e);
        failedKeys.add(key);
      }
    }
    return new DefaultBlueprintAddResultDto(added, skipped, failedKeys);
  }

  /**
   * Fetches the current default-blueprint set, collapsing a backend failure to an empty list rather
   * than a 500.
   *
   * @return the default set, or an empty list on failure
   */
  private List<DefaultBlueprintDto> fetchDefaults() {
    try {
      List<DefaultBlueprintDto> result =
          backendApiClient.get(BACKEND_BASE, DEFAULT_BLUEPRINT_LIST_TYPE);
      return result == null ? Collections.emptyList() : result;
    } catch (BackendServiceException e) {
      log.debug("Failed to fetch default blueprints", e);
      return Collections.emptyList();
    } catch (Exception e) {
      log.error("Failed to fetch default blueprints", e);
      return Collections.emptyList();
    }
  }
}
