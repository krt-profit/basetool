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

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * JSON proxies for the catalog pickers' live search (REQ-FE-016): material and location comboboxes
 * fetch their options on demand through these endpoints instead of preloading the full catalog into
 * the page. Every entry stays reachable by typing regardless of catalog size — the deliberate
 * alternative to a preloaded (or silently capped) option list, mirroring the orderable item search
 * in {@link JobOrderPageController#itemSearch(String)}. Both relays fetch one row more than their
 * combobox renders ({@link PickerSearch}) so an overflow is detectable and announced, and degrade
 * to an empty list on backend failure so the picker shows "no matches" instead of an error page.
 * Public ({@code /catalog/**} is {@code permitAll}) because the anonymous order form carries a
 * material picker; the backend endpoints are public catalog data themselves.
 */
@Controller
@RequestMapping("/catalog")
@RequiredArgsConstructor
@Slf4j
public class CatalogSearchController {

  /** Response type of the backend material picker search ({@code GET /api/v1/materials/search}). */
  private static final ParameterizedTypeReference<PageResponse<MaterialDto>> PAGE_OF_MATERIAL =
      new ParameterizedTypeReference<PageResponse<MaterialDto>>() {};

  /** Response type of the backend location picker search ({@code GET /api/v1/locations/search}). */
  private static final ParameterizedTypeReference<PageResponse<LocationReferenceDto>>
      PAGE_OF_LOCATION_REFERENCE =
          new ParameterizedTypeReference<PageResponse<LocationReferenceDto>>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Live search over the visible material catalog for the searchable material pickers. {@code
   * jobOrder=true} narrows to the job-order subset (the orders material lines), {@code raw=true} to
   * refinery inputs (RAW or manually raw-flagged) — mutually independent flags matching the
   * backend's picker search. Returns full {@link MaterialDto}s because the pickers mirror option
   * metadata (quantity type, refined material) off the results.
   *
   * @param q the case-insensitive material-name fragment ({@code null}/blank = first page)
   * @param jobOrder when true, only job-order materials
   * @param raw when true, only refinery input materials
   * @return up to {@link PickerSearch#PAGE_SIZE} matching visible materials, name ascending; empty
   *     on failure. The combobox renders {@link PickerSearch#RENDER_CAP} of them and turns the
   *     extra row into the "keep typing" hint rather than showing it.
   */
  @GetMapping("/material-search")
  @ResponseBody
  public List<MaterialDto> materialSearch(
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "false") boolean jobOrder,
      @RequestParam(required = false, defaultValue = "false") boolean raw) {
    try {
      PageResponse<MaterialDto> page =
          backendApiClient.get(
              "/api/v1/materials/search?search={q}&jobOrderOnly={jobOrder}&rawOnly={raw}"
                  + "&size="
                  + PickerSearch.PAGE_SIZE
                  + "&sort=name,asc",
              PAGE_OF_MATERIAL,
              q == null ? "" : q,
              jobOrder,
              raw);
      return page != null && page.content() != null ? page.content() : List.of();
    } catch (Exception e) {
      log.error("Failed to search materials", e);
      return List.of();
    }
  }

  /**
   * Live search over the non-hidden location catalog for the searchable location pickers.
   *
   * <p>Fetches {@link PickerSearch#LOCATION_PAGE_SIZE} rows, not the generic {@link
   * PickerSearch#PAGE_SIZE}: the location catalogue is small and bounded by the game universe, and
   * a booking user expects to scroll it rather than guess a search term. At the shipped {@code
   * size=25} against a render cap of 50 this relay was a silent cap — 28 of the 53 visible
   * locations (MIC-L5, Patch City, New Babbage, Orison, …) never appeared in the Lager Einbuchen
   * picker, with nothing on screen indicating the list was cut.
   *
   * @param q the case-insensitive location-name fragment ({@code null}/blank = first page)
   * @return up to {@link PickerSearch#LOCATION_PAGE_SIZE} matching non-hidden location references,
   *     name ascending; empty on failure
   */
  @GetMapping("/location-search")
  @ResponseBody
  public List<LocationReferenceDto> locationSearch(@RequestParam(required = false) String q) {
    try {
      PageResponse<LocationReferenceDto> page =
          backendApiClient.get(
              "/api/v1/locations/search?search={q}&size="
                  + PickerSearch.LOCATION_PAGE_SIZE
                  + "&sort=name,asc",
              PAGE_OF_LOCATION_REFERENCE,
              q == null ? "" : q);
      return page != null && page.content() != null ? page.content() : List.of();
    } catch (Exception e) {
      log.error("Failed to search locations", e);
      return List.of();
    }
  }
}
