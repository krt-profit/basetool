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
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapLinkRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapObjectAdminDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapPlaceLinkDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapVisibilityRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the admin starmap (REQ-P4K-034…038): the starmap objects and their
 * visibility, the links of the UEX location rows, and a storage place created on an object.
 */
@Service
@RequiredArgsConstructor
public class StarmapBackendClient {

  /** The admin starmap base path. */
  private static final String BASE = "/api/v1/catalog/admin/starmap";

  /** The paging query every list carries. */
  private static final String PAGING = "?page={page}&size={size}";

  private static final ParameterizedTypeReference<PageResponse<StarmapObjectAdminDto>> OBJECT_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<StarmapPlaceLinkDto>> PLACE_PAGE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Reads one page of the starmap objects ordered by class name; an absent filter is left out of
   * the query, so the backend reads it as "all".
   *
   * @param starSystem the star system folder, or {@code null}
   * @param type the P4K type, or {@code null}
   * @param query a fragment of the class name or either name, or {@code null}
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<StarmapObjectAdminDto> objects(
      @Nullable String starSystem,
      @Nullable String type,
      @Nullable String query,
      int page,
      int size) {
    StringBuilder uri = new StringBuilder(BASE).append("/objects").append(PAGING);
    List<Object> variables = new ArrayList<>(List.of(page, size));
    if (starSystem != null) {
      uri.append("&starSystem={starSystem}");
      variables.add(starSystem);
    }
    if (type != null) {
      uri.append("&type={type}");
      variables.add(type);
    }
    if (query != null) {
      uri.append("&query={query}");
      variables.add(query);
    }
    return backendApiClient.get(uri.toString(), OBJECT_PAGE, variables.toArray());
  }

  /**
   * Sets or clears an object's visibility override.
   *
   * @param id the object
   * @param request the override and the version the admin saw
   * @return the object after the change, or {@code null} when the backend sent no body
   */
  @Nullable
  public StarmapObjectAdminDto overrideVisibility(
      @NotNull UUID id, @NotNull StarmapVisibilityRequest request) {
    return backendApiClient.put(
        BASE + "/objects/{id}/visibility", request, StarmapObjectAdminDto.class, id);
  }

  /**
   * Reads one page of a UEX location table's rows with their links, ordered by name.
   *
   * @param kind the table, e.g. {@code CITY}
   * @param query a fragment of the row name, or {@code null}
   * @param unlinkedOnly whether only unlinked rows are listed
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<StarmapPlaceLinkDto> places(
      @NotNull String kind, @Nullable String query, boolean unlinkedOnly, int page, int size) {
    String template = BASE + "/places/{kind}" + PAGING + "&unlinkedOnly={unlinkedOnly}";
    return query == null
        ? backendApiClient.get(template, PLACE_PAGE, kind, page, size, unlinkedOnly)
        : backendApiClient.get(
            template + "&query={query}", PLACE_PAGE, kind, page, size, unlinkedOnly, query);
  }

  /**
   * Sets, clears or releases a row's link.
   *
   * @param kind the table
   * @param id the row
   * @param request the change and the link the admin saw
   * @return the row after the change, or {@code null} when the backend sent no body
   */
  @Nullable
  public StarmapPlaceLinkDto changeLink(
      @NotNull String kind, @NotNull UUID id, @NotNull StarmapLinkRequest request) {
    return backendApiClient.put(
        BASE + "/places/{kind}/{id}/link", request, StarmapPlaceLinkDto.class, kind, id);
  }

  /**
   * Creates a storage place on a visible starmap object (REQ-P4K-036).
   *
   * @param location the place to create, carrying its {@code starmapObjectId}
   * @return the created location, or {@code null} when the backend sent no body
   */
  @Nullable
  public LocationDto createStoragePlace(@NotNull LocationDto location) {
    return backendApiClient.post("/api/v1/locations", location, LocationDto.class);
  }
}
