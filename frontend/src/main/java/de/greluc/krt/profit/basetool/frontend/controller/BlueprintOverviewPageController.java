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

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintOverviewOwnerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Page controller for the org-unit blueprint availability overview (#364): renders which blueprints
 * are available among the members of the caller's oversight org units, and proxies the lazy owner
 * drill-down. The backend ({@code /api/v1/personal-blueprints/overview}) is the security boundary —
 * it returns data only to admins, officers (their Staffel) and Spezialkommando leads (their SK);
 * the sidebar entry is hidden from everyone else via the {@code canSeeBlueprintOverview} model
 * attribute. A direct hit by a non-eligible caller degrades to an empty page rather than a stack
 * trace.
 */
@Controller
@RequestMapping("/blueprint-overview")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class BlueprintOverviewPageController {

  /** Selectable page sizes (REQ-INV-013); requests with any other size fall back to the default. */
  private static final List<Integer> PAGE_SIZES = List.of(10, 50, 100);

  /** Page size applied when the request carries none (or a non-whitelisted one). */
  private static final int DEFAULT_PAGE_SIZE = 50;

  /**
   * Response type for one server-side page of the blueprint availability list. A shared static
   * {@link ParameterizedTypeReference} is behaviourally identical to a fresh anonymous instance per
   * call (Q10).
   */
  private static final ParameterizedTypeReference<PageResponse<BlueprintOverviewEntryDto>>
      OVERVIEW_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for the lazy owner drill-down list returned by {@code /overview/owners}. */
  private static final ParameterizedTypeReference<List<BlueprintOverviewOwnerDto>> OWNER_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders one server-side page of the blueprint availability list (one row per product, with the
   * owning-member count and a lazy owner drill-down). {@code size} is restricted to {@link
   * #PAGE_SIZES} so the query string cannot turn the page into the unbounded fetch this view used
   * to do; the optional {@code search} is relayed to the backend, which filters before pagination
   * so the search spans every entry. A backend failure collapses to an empty list plus an error
   * banner.
   *
   * @param page zero-based page index, defaulted/clamped to 0
   * @param size requested page size; only {@link #PAGE_SIZES} are honoured
   * @param search optional case-insensitive product-name fragment
   * @param fragment when {@code "results"} only the table + pagination fragment is rendered (AJAX
   *     filter/paging swap, REQ-FE-002); otherwise the full page is returned
   * @param model Thymeleaf model populated with the page content, the page envelope ({@code
   *     overviewPage}), and the echoed {@code search}/{@code pageSizes} for the filter form and
   *     size picker
   * @return the {@code blueprint-overview} view name, or its {@code results} fragment for an AJAX
   *     swap request
   */
  @GetMapping
  public String view(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String fragment,
      Model model) {
    int effectivePage = page == null || page < 0 ? 0 : page;
    int effectiveSize = size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;
    String trimmedSearch = search == null || search.isBlank() ? null : search.trim();
    List<BlueprintOverviewEntryDto> overview = new ArrayList<>();
    PageResponse<BlueprintOverviewEntryDto> res = null;
    try {
      String uri =
          "/api/v1/personal-blueprints/overview?page=" + effectivePage + "&size=" + effectiveSize;
      res =
          trimmedSearch != null
              ? backendApiClient.get(uri + "&search={search}", OVERVIEW_PAGE_TYPE, trimmedSearch)
              : backendApiClient.get(uri, OVERVIEW_PAGE_TYPE);
      if (res != null && res.content() != null) {
        overview = new ArrayList<>(res.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch blueprint availability overview", e);
      model.addAttribute("error", "error.blueprintOverview.load");
    }
    model.addAttribute("overview", overview);
    model.addAttribute("overviewPage", res);
    model.addAttribute("search", trimmedSearch);
    model.addAttribute("pageSizes", PAGE_SIZES);
    return "results".equals(fragment) ? "blueprint-overview :: results" : "blueprint-overview";
  }

  /**
   * Lazy owner drill-down proxy: relays to the backend owners endpoint for one product and returns
   * the in-scope owners' display names as JSON. Failures collapse to an empty list so the expand
   * panel renders a graceful "no data" state rather than a stack trace.
   *
   * @param productKey the normalized product key whose owners to list
   * @return the owning in-scope members, or an empty list on any backend failure
   */
  @GetMapping("/owners")
  @ResponseBody
  public List<BlueprintOverviewOwnerDto> owners(@RequestParam String productKey) {
    try {
      List<BlueprintOverviewOwnerDto> owners =
          backendApiClient.get(
              "/api/v1/personal-blueprints/overview/owners?productKey={productKey}",
              OWNER_LIST_TYPE,
              productKey);
      return owners != null ? owners : List.of();
    } catch (Exception e) {
      log.warn(
          "Failed to fetch blueprint owners for productKey='{}': {}", productKey, e.getMessage());
      return List.of();
    }
  }
}
