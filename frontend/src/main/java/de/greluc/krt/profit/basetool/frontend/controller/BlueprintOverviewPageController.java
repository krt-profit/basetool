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
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintOverviewOwnerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Page controller for the org-unit blueprint availability overview and its lazy owner drill-down.
 * The backend enforces access; a non-eligible caller gets an empty page.
 */
@Controller
@UsesLayoutModel
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
   * Renders one page of the blueprint availability list. {@code size} is limited to {@link
   * #PAGE_SIZES}; {@code search} is filtered by the backend before pagination. A backend failure
   * yields an empty list plus an error banner.
   *
   * @param page zero-based page index, defaulted/clamped to 0
   * @param size requested page size; only {@link #PAGE_SIZES} are honoured
   * @param search optional case-insensitive product-name fragment
   * @param fragment {@code "results"} renders only the table + pagination fragment (REQ-FE-002)
   * @param model model populated with the page content, {@code overviewPage}, {@code search} and
   *     {@code pageSizes}
   * @return the {@code blueprint-overview} view name, or its {@code results} fragment
   */
  @NotNull
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
   * Relays the lazy owner drill-down for one product as JSON.
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
