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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.ShopBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShopAdminDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShopTerminalLinkRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShopTerminalOptionDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.kernel.web.StringNormalization;
import jakarta.validation.Valid;
import java.util.List;
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
 * JSON proxy of the {@code /admin/shops} page (REQ-P4K-042, REQ-P4K-043): the page reads the shop
 * list and the terminal search and swaps a changed row in place through {@code krtFetch}. A backend
 * refusal of a write, a stale link included, is relayed as {@code application/problem+json} with
 * its status.
 */
@RestController
@RequestMapping("/admin/shops")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminShopsController {

  /** The page size of the shop list. */
  private static final int PAGE_SIZE = 25;

  /** Relays the shop reads and writes. */
  private final ShopBackendClient shopClient;

  /**
   * Lists one page of the shops; a blank filter means "all".
   *
   * @param starSystem the star system, or blank
   * @param query a fragment of the entity or registry name, or blank
   * @param unlinkedOnly whether only shops without a terminal are listed
   * @param page zero-based page index
   * @return the page of shops
   */
  @GetMapping("/entries")
  @Nullable
  public PageResponse<ShopAdminDto> shops(
      @RequestParam(required = false) @Nullable String starSystem,
      @RequestParam(required = false) @Nullable String query,
      @RequestParam(defaultValue = "false") boolean unlinkedOnly,
      @RequestParam(defaultValue = "0") int page) {
    return withBackendStatus(() ->
        shopClient.shops(
            StringNormalization.trimToNull(starSystem),
            StringNormalization.trimToNull(query),
            unlinkedOnly,
            page,
            PAGE_SIZE));
  }

  /**
   * Finds the UEX terminals whose name contains a fragment; a blank fragment finds none.
   *
   * @param query the fragment, or blank
   * @return the terminals in name order
   */
  @GetMapping("/terminals")
  @Nullable
  public List<ShopTerminalOptionDto> terminals(
      @RequestParam(required = false) @Nullable String query) {
    String fragment = StringNormalization.trimToNull(query);
    return fragment == null ? List.of() : withBackendStatus(() -> shopClient.terminals(fragment));
  }

  /**
   * Sets, clears or releases a shop's terminal link; a link that changed since the admin saw it is
   * relayed as the backend's {@code 409}, a set without a terminal as its {@code 400}.
   *
   * @param id the shop
   * @param request the change and the link the admin saw
   * @return the shop after the change, or the backend's refusal
   */
  @PostMapping("/{id}/terminal-link")
  @NotNull
  public ResponseEntity<Object> changeLink(
      @PathVariable("id") @NotNull UUID id,
      @RequestBody @Valid @NotNull ShopTerminalLinkRequest request) {
    return relay(
        log,
        "shop terminal link change",
        () -> ResponseEntity.ok(shopClient.changeLink(id, request)));
  }
}
