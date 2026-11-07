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

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShopAdminDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShopTerminalLinkRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShopTerminalOptionDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the admin shops (REQ-P4K-042, REQ-P4K-043): the P4K shops with their UEX
 * terminal links, a terminal search, and a link set, cleared or released by hand.
 */
@Service
@RequiredArgsConstructor
public class ShopBackendClient {

  /** The admin shops base path. */
  private static final String BASE = "/api/v1/catalog/admin/shops";

  private static final ParameterizedTypeReference<PageResponse<ShopAdminDto>> SHOP_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<ShopTerminalOptionDto>> TERMINALS =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Reads one page of the shops ordered by registry name; an absent filter is left out of the
   * query, so the backend reads it as "all".
   *
   * @param starSystem the star system, or {@code null}
   * @param query a fragment of the entity or registry name, or {@code null}
   * @param unlinkedOnly whether only shops without a terminal are listed
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<ShopAdminDto> shops(
      @Nullable String starSystem,
      @Nullable String query,
      boolean unlinkedOnly,
      int page,
      int size) {
    QueryTemplate uri =
        new QueryTemplate(BASE)
            .param("page", page)
            .param("size", size)
            .param("unlinkedOnly", unlinkedOnly)
            .param("starSystem", starSystem)
            .param("query", query);
    return backendApiClient.get(uri.template(), SHOP_PAGE, uri.variables());
  }

  /**
   * Finds the UEX terminals whose name contains a fragment.
   *
   * @param query the fragment
   * @return the terminals, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ShopTerminalOptionDto> terminals(@NotNull String query) {
    return backendApiClient.get(BASE + "/terminals?query={query}", TERMINALS, query);
  }

  /**
   * Sets, clears or releases a shop's terminal link.
   *
   * @param id the shop
   * @param request the change and the link the admin saw
   * @return the shop after the change, or {@code null} when the backend sent no body
   */
  @Nullable
  public ShopAdminDto changeLink(@NotNull UUID id, @NotNull ShopTerminalLinkRequest request) {
    return backendApiClient.put(BASE + "/{id}/terminal-link", request, ShopAdminDto.class, id);
  }
}
