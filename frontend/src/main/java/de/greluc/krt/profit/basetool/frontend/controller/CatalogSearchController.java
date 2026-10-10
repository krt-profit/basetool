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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

  /** Runs the material and location picker searches. */
  private final CatalogueBackendClient catalogueClient;

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
          catalogueClient.searchMaterials(
              q == null ? "" : q, jobOrder, raw, PickerSearch.PAGE_SIZE);
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
          catalogueClient.searchLocations(q == null ? "" : q, PickerSearch.LOCATION_PAGE_SIZE);
      return page != null && page.content() != null ? page.content() : List.of();
    } catch (Exception e) {
      log.error("Failed to search locations", e);
      return List.of();
    }
  }
}
