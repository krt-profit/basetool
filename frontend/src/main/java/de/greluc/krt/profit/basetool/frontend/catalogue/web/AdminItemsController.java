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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;
import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.GameItemAdminBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.GameItemAdminDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.GameItemObtainabilityRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.kernel.web.StringNormalization;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON proxy of the {@code /admin/items} page (REQ-P4K-048): the page reads the item list and swaps
 * every changed row in place through {@code krtFetch}. A backend refusal, a running import or sync
 * included, is relayed as {@code application/problem+json} with its status.
 *
 * <p>Every successful change evicts {@link CacheDomain#ITEM_CATALOG}, the probe of the item-order
 * picker the change can empty.
 */
@RestController
@RequestMapping("/admin/items")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminItemsController {

  /** The page size of the item list. */
  private static final int PAGE_SIZE = 25;

  /** Relays the item reads and writes. */
  private final GameItemAdminBackendClient itemClient;

  /** Evicts the item-order picker probe after a change. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Lists one page of the game items; a blank search means "all".
   *
   * @param query a fragment of the item name, or blank
   * @param unobtainableOnly whether only the items an admin hid as not obtainable are listed
   * @param page zero-based page index
   * @return the page of items
   */
  @GetMapping("/list")
  @Nullable
  public PageResponse<GameItemAdminDto> items(
      @RequestParam(required = false) @Nullable String query,
      @RequestParam(defaultValue = "false") boolean unobtainableOnly,
      @RequestParam(defaultValue = "0") int page) {
    return withBackendStatus(() ->
        itemClient.items(
            StringNormalization.trimToNull(query), unobtainableOnly, page, PAGE_SIZE));
  }

  /**
   * Hides an item as not obtainable in the game, or offers it again; a running import or sync is
   * relayed as the backend's {@code 409}.
   *
   * @param id the item
   * @param request whether the pickers offer the item
   * @return the item after the change, or the backend's refusal
   */
  @PostMapping("/{id}/obtainability")
  @NotNull
  public ResponseEntity<Object> setObtainability(
      @PathVariable("id") @NotNull UUID id,
      @RequestBody @Valid @NotNull GameItemObtainabilityRequest request) {
    return relay(
        log,
        "game item obtainability change",
        () -> {
          GameItemAdminDto updated = itemClient.setObtainability(id, request);
          cacheEviction.evict(CacheDomain.ITEM_CATALOG);
          return ResponseEntity.ok(updated);
        });
  }
}
