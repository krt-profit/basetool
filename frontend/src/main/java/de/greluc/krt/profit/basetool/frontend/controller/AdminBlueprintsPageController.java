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
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller backing the {@code /admin/blueprints} page: a paginated, filterable list of
 * the synced SC Wiki crafting blueprints with their ingredients and per-slot stat modifiers.
 *
 * <p>Admin-only — class-level {@code @PreAuthorize("hasRole('ADMIN')")} matches the backend gate.
 * Read-only: the SC Wiki sync is the only writer. Filtering and paging are server-side (relayed to
 * {@code GET /api/v1/blueprints}); a backend failure collapses to an error banner with an empty
 * list rather than a 500.
 */
@Controller
@RequestMapping("/admin/blueprints")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminBlueprintsPageController {

  /** Page size for the blueprint list — one detail-rich card per row, so kept modest. */
  private static final int PAGE_SIZE = 25;

  /**
   * Character budget for the search term when it is written to a log line. An output-name or
   * Wiki-key filter an admin could plausibly be typing fits comfortably; anything longer is a paste
   * or an attack and is truncated by {@link LogSafe#text(String, int)} rather than allowed to
   * stretch the line.
   */
  private static final int MAX_LOGGED_QUERY = 80;

  /** Response type for the paginated blueprint list ({@code GET /api/v1/blueprints}). */
  private static final ParameterizedTypeReference<PageResponse<BlueprintDto>> BLUEPRINT_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Loads one page of blueprints, optionally filtered by an output-item-name / Wiki-key substring,
   * and populates the model for the {@code admin/blueprints} view.
   *
   * @param search optional case-insensitive output-name / key filter
   * @param page zero-based page index
   * @param fragment when {@code "results"} only the toolbar + table + pager fragment is rendered
   *     (AJAX filter/paging swap, REQ-FE-002); otherwise the full page is returned
   * @param model Thymeleaf model
   * @return the {@code admin/blueprints} view name, or its {@code results} fragment for an AJAX
   *     swap request
   */
  @GetMapping
  public String listBlueprints(
      @RequestParam(required = false) String search,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false) String fragment,
      Model model) {
    int safePage = Math.max(page, 0);
    String trimmed = (search == null || search.isBlank()) ? null : search.trim();

    StringBuilder uri =
        new StringBuilder("/api/v1/blueprints?size=")
            .append(PAGE_SIZE)
            .append("&page=")
            .append(safePage)
            .append("&sort=outputName,asc");
    boolean hasSearch = trimmed != null;
    if (hasSearch) {
      // Pass the free-text term as a WebClient URI-template variable so it is percent-encoded
      // exactly once across the frontend->backend hop. URLEncoder form-encoding (space -> '+')
      // double-encodes umlauts / reserved chars when re-encoded on the hop, yielding zero matches
      // (see BackendApiClient#get(String, ParameterizedTypeReference, Object...)).
      uri.append("&search={search}");
    }

    try {
      PageResponse<BlueprintDto> response =
          hasSearch
              ? backendApiClient.get(uri.toString(), BLUEPRINT_PAGE_TYPE, trimmed)
              : backendApiClient.get(uri.toString(), BLUEPRINT_PAGE_TYPE);
      if (response != null) {
        model.addAttribute(
            "blueprints", response.content() == null ? List.of() : response.content());
        model.addAttribute("currentPage", response.page());
        model.addAttribute("totalPages", response.totalPages());
        model.addAttribute("totalElements", response.totalElements());
      } else {
        populateEmpty(model);
      }
    } catch (BackendServiceException e) {
      // The search term is admin-typed free text: sanitised before it reaches the logger so a
      // pasted newline cannot fabricate a second log line (CWE-117). Level unchanged — an
      // unreachable backend is an expected, already-metered failure.
      log.debug(
          "Error loading blueprints data (search={})", LogSafe.text(trimmed, MAX_LOGGED_QUERY), e);
      model.addAttribute("error", "error.admin.blueprints.load");
      populateEmpty(model);
    } catch (Exception e) {
      // Stays ERROR: this is the catch(Exception) catch-all for a genuinely unexpected failure,
      // which REQ-OBS-001 sanctions at ERROR. Same sanitising as the branch above.
      log.error(
          "Error loading blueprints data (search={})", LogSafe.text(trimmed, MAX_LOGGED_QUERY), e);
      model.addAttribute("error", "error.admin.blueprints.load");
      populateEmpty(model);
    }
    model.addAttribute("search", trimmed == null ? "" : trimmed);
    return "results".equals(fragment) ? "admin/blueprints :: results" : "admin/blueprints";
  }

  /**
   * Fills the paging model attributes with an empty result so the template never dereferences a
   * missing attribute on a backend miss / failure.
   *
   * @param model Thymeleaf model to fill
   */
  private void populateEmpty(Model model) {
    model.addAttribute("blueprints", List.of());
    model.addAttribute("currentPage", 0);
    model.addAttribute("totalPages", 0);
    model.addAttribute("totalElements", 0L);
  }
}
