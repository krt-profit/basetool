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

import de.greluc.krt.profit.basetool.frontend.catalogue.model.GameItemAdminDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.GameItemObtainabilityRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the admin item list (REQ-P4K-048): the catalogue's game items with their
 * obtainability, and an item hidden as not obtainable or offered again.
 */
@Service
@RequiredArgsConstructor
public class GameItemAdminBackendClient {

  /** The admin game-item base path. */
  private static final String BASE = "/api/v1/catalog/admin/game-items";

  private static final ParameterizedTypeReference<PageResponse<GameItemAdminDto>> ITEM_PAGE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Reads one page of the game items in name order; an absent search is left out of the query, so
   * the backend reads it as "all".
   *
   * @param query a fragment of the item name, or {@code null}
   * @param unobtainableOnly whether only the items an admin hid as not obtainable are listed
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<GameItemAdminDto> items(
      @Nullable String query, boolean unobtainableOnly, int page, int size) {
    QueryTemplate uri =
        new QueryTemplate(BASE)
            .param("page", page)
            .param("size", size)
            .param("unobtainableOnly", unobtainableOnly)
            .param("query", query);
    return backendApiClient.get(uri.template(), ITEM_PAGE, uri.variables());
  }

  /**
   * Hides a game item as not obtainable in the game, or offers it again.
   *
   * @param id the item
   * @param request whether the pickers offer the item
   * @return the item after the change, or {@code null} when the backend sent no body
   */
  @Nullable
  public GameItemAdminDto setObtainability(
      @NotNull UUID id, @NotNull GameItemObtainabilityRequest request) {
    return backendApiClient.put(BASE + "/{id}/obtainability", request, GameItemAdminDto.class, id);
  }
}
