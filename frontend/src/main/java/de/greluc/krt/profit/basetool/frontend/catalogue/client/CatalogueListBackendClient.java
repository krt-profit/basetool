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

package de.greluc.krt.profit.basetool.frontend.catalogue.client;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the paginated catalogue lists on {@code /admin/materials}, {@code
 * /admin/locations} and {@code /ship-data}: one displayed page, hidden rows included, sorted by
 * name and optionally narrowed by a name search (REQ-ADMIN-004).
 */
@Service
@RequiredArgsConstructor
public class CatalogueListBackendClient {

  private static final ParameterizedTypeReference<PageResponse<MaterialDto>> MATERIAL_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<LocationDto>> LOCATION_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<ShipTypeDto>> SHIP_TYPE_PAGE =
      new ParameterizedTypeReference<>() {};

  /** The backend seam every catalogue list read goes through. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads one displayed page of the admin material list.
   *
   * @param query name fragment to narrow the list, or {@code null} for every material
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialDto> materials(@Nullable String query, int page, int size) {
    return listPage("/api/v1/materials", MATERIAL_PAGE, query, page, size);
  }

  /**
   * Reads one displayed page of the admin location list.
   *
   * @param query name fragment to narrow the list, or {@code null} for every location
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<LocationDto> locations(@Nullable String query, int page, int size) {
    return listPage("/api/v1/locations", LOCATION_PAGE, query, page, size);
  }

  /**
   * Reads one displayed page of the ship-type list.
   *
   * @param query name fragment to narrow the list, or {@code null} for every ship type
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<ShipTypeDto> shipTypes(@Nullable String query, int page, int size) {
    return listPage("/api/v1/ship-types", SHIP_TYPE_PAGE, query, page, size);
  }

  @Nullable
  private <T> PageResponse<T> listPage(
      @NotNull String path,
      @NotNull ParameterizedTypeReference<PageResponse<T>> type,
      @Nullable String query,
      int page,
      int size) {
    String template = path + "?includeHidden=true&sort=name,asc&page={page}&size={size}";
    return query == null
        ? backendApiClient.get(template, type, page, size)
        : backendApiClient.get(template + "&query={query}", type, page, size, query);
  }
}
