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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Members-only JSON proxies for the material and location pickers' live search (REQ-FE-016).
 *
 * <p>Each relay fetches one row more than the combobox renders ({@link PickerSearch}) so overflow
 * can be announced, and returns an empty list on backend failure (REQ-SEC-052).
 */
@RestController
@RequestMapping("/catalog")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
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
   * Live search over the visible material catalog. {@code jobOrder=true} and {@code raw=true}
   * independently narrow to job-order materials and refinery inputs.
   *
   * @param q the case-insensitive material-name fragment ({@code null}/blank = first page)
   * @param jobOrder when true, only job-order materials
   * @param raw when true, only refinery input materials
   * @return up to {@link PickerSearch#PAGE_SIZE} matching visible materials, name ascending; empty
   *     on failure
   */
  @GetMapping("/material-search")
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
   * Live search over the non-hidden location catalog, fetching {@link
   * PickerSearch#LOCATION_PAGE_SIZE} rows so the small catalog is fully browsable.
   *
   * @param q the case-insensitive location-name fragment ({@code null}/blank = first page)
   * @return up to {@link PickerSearch#LOCATION_PAGE_SIZE} matching non-hidden location references,
   *     name ascending; empty on failure
   */
  @GetMapping("/location-search")
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
