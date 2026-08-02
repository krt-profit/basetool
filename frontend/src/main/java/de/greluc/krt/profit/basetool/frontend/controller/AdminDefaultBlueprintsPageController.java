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

import de.greluc.krt.profit.basetool.frontend.logging.LogSafe;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintProductDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.DefaultBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.DefaultBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin page for curating the auto-granted default-blueprint set (REQ-INV-017). Renders the current
 * set, proxies the blueprint product type-ahead, and relays add / remove to the backend admin API
 * via {@link BackendApiClient}. ADMIN-gated at the class level; the grant-to-everyone side effect
 * of an add lives in the backend service.
 *
 * <p>Mutations use a classic POST→redirect so the list and the flash toast resync on every change
 * without per-row DOM patching — the set is small and admin-only, so a reload is cheap.
 */
@Controller
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
  @GetMapping
  public String view(Model model) {
    model.addAttribute("defaults", fetchDefaults());
    return "admin/default-blueprints";
  }

  /**
   * Type-ahead proxy backing the add bar. Relays the query to the backend blueprint product search
   * (the same endpoint the personal-blueprint add uses); failures collapse to an empty list so the
   * type-ahead never shows a stack trace.
   *
   * @param q optional case-insensitive product-name substring
   * @param limit optional result cap; defaults to 25 and is clamped to {@code [1, 200]}
   * @return the matching products, or an empty list on any backend failure
   */
  @GetMapping("/search")
  @ResponseBody
  public List<BlueprintProductDto> search(
      @RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
    try {
      String query = q == null ? "" : q;
      int effectiveLimit = limit == null ? 25 : Math.min(200, Math.max(1, limit));
      // Pass the free-text term as a WebClient URI-template variable so it is percent-encoded
      // exactly once across the frontend->backend hop; URLEncoder form-encoding (space -> '+')
      // double-encodes umlauts / reserved chars when re-encoded on the hop, yielding zero matches.
      String uri = "/api/v1/blueprints/products/search?q={q}&limit=" + effectiveLimit;
      List<BlueprintProductDto> result =
          backendApiClient.get(uri, BLUEPRINT_PRODUCT_LIST_TYPE, query);
      return result == null ? Collections.emptyList() : result;
    } catch (Exception e) {
      // DEBUG, not WARN: this endpoint fires ONE REQUEST PER KEYSTROKE. With the backend down, a
      // single admin typing a product name produces one line per character — a log-flood vector
      // triggered accidentally by ordinary use, which REQ-OBS-001 puts at DEBUG. The outage itself
      // is already carried by basetool_backend_client_errors_total and by the WebClient/resilience
      // logging, so nothing is lost. The query is user-typed free text and goes through LogSafe so
      // it cannot inject a forged log line (CWE-117).
      log.debug(
          "Default-blueprint product type-ahead failed for query='{}': {}",
          LogSafe.text(q, MAX_LOGGED_QUERY),
          e.getMessage());
      return Collections.emptyList();
    }
  }

  /**
   * Adds the staged products to the default set, relaying one backend add per key. Keys already in
   * the set (409) are silently skipped; the flash toast reports the outcome.
   *
   * @param productKeys the normalized product keys staged by the admin
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the default-blueprints page
   */
  @PostMapping("/add")
  public String add(
      @RequestParam(name = "productKeys", required = false) List<String> productKeys,
      RedirectAttributes redirectAttributes) {
    List<String> keys = productKeys == null ? List.of() : productKeys;
    int added = 0;
    boolean failed = false;
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      try {
        backendApiClient.post(
            "/api/v1/admin/default-blueprints",
            new DefaultBlueprintCreateRequest(key.trim()),
            DefaultBlueprintDto.class);
        added++;
      } catch (BackendServiceException e) {
        if (e.getStatusCode() == 409) {
          // Already a default — skip silently.
          continue;
        }
        log.debug("Failed to add default blueprint '{}': {}", key, e.getMessage());
        failed = true;
      } catch (Exception e) {
        log.error("Failed to add default blueprint '{}'", key, e);
        failed = true;
      }
    }
    if (failed) {
      redirectAttributes.addFlashAttribute("errorToast", "admin.defaultBlueprints.error.add");
    } else if (added > 0) {
      redirectAttributes.addFlashAttribute("successToast", "admin.defaultBlueprints.toast.added");
    } else {
      redirectAttributes.addFlashAttribute(
          "successToast", "admin.defaultBlueprints.toast.noneAdded");
    }
    return "redirect:/admin/default-blueprints";
  }

  /**
   * Removes a product from the default set. Users keep blueprints already granted to them.
   *
   * @param id default-blueprint entry id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the default-blueprints page
   */
  @PostMapping("/{id}/delete")
  public String remove(@PathVariable String id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete(
          "/api/v1/admin/default-blueprints/" + URLEncoder.encode(id, StandardCharsets.UTF_8),
          Void.class);
      redirectAttributes.addFlashAttribute("successToast", "admin.defaultBlueprints.toast.removed");
    } catch (Exception e) {
      log.error("Failed to remove default blueprint {}", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "admin.defaultBlueprints.error.remove");
    }
    return "redirect:/admin/default-blueprints";
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
          backendApiClient.get("/api/v1/admin/default-blueprints", DEFAULT_BLUEPRINT_LIST_TYPE);
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
