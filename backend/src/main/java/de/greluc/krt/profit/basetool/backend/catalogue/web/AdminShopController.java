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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.ShopAdminDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ShopAdminService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ShopTerminalOptionDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.StarmapLinkAction;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only P4K shop endpoints (REQ-P4K-042, REQ-P4K-043, ADR-0347).
 *
 * <ul>
 *   <li>{@code GET /} — the shops with their UEX terminal links, filtered and paged.
 *   <li>{@code GET /terminals} — the UEX terminals whose name contains a fragment.
 *   <li>{@code PUT /{id}/terminal-link} — set, clear or release a shop's link by hand.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/catalog/admin/shops")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(
    name = "Admin – Shops",
    description = "Administrator endpoints for the P4K shops and their links to the UEX terminals.")
@SecurityRequirement(name = "bearer-jwt")
public class AdminShopController {

  private final ShopAdminService service;

  /**
   * An admin's change of a shop's terminal link, compare-and-set on the link the admin saw.
   *
   * @param action set, clear or release
   * @param terminalId the terminal to link for {@code SET}, else ignored
   * @param expectedTerminalId the link the admin saw, or {@code null}
   * @param expectedManual the manual flag the admin saw
   */
  public record ShopTerminalLinkRequest(
      @NotNull StarmapLinkAction action,
      @Nullable UUID terminalId,
      @Nullable UUID expectedTerminalId,
      @NotNull Boolean expectedManual) {}

  /**
   * Pages the shops.
   *
   * @param starSystem the star system, or all
   * @param query a fragment of the entity or registry name, or none
   * @param unlinkedOnly whether only shops without a terminal are listed
   * @param page zero-based page index
   * @param size page size
   * @param sort sort terms over {@code entityName}, {@code registryName} and {@code starSystem}
   * @return the page of shops
   */
  @GetMapping
  @Operation(summary = "List the P4K shops with their UEX terminal links.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The shops."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public PageResponse<ShopAdminDto> shops(
      @RequestParam(required = false) @Nullable String starSystem,
      @RequestParam(required = false) @Nullable String query,
      @RequestParam(required = false, defaultValue = "false") boolean unlinkedOnly,
      @RequestParam(required = false) @Nullable Integer page,
      @RequestParam(required = false) @Nullable Integer size,
      @RequestParam(required = false) @Nullable String sort) {
    return PageResponse.of(
        service.shops(
            starSystem,
            StringNormalization.trimToNull(query),
            unlinkedOnly,
            PaginationUtil.createPageRequest(
                page,
                size,
                sort,
                Set.of("entityName", "registryName", "starSystem"),
                "registryName")));
  }

  /**
   * Finds UEX terminals to link a shop to.
   *
   * @param query a fragment of the terminal's name
   * @return the matching terminals in name order
   */
  @GetMapping("/terminals")
  @Operation(summary = "Find the UEX terminals whose name contains a fragment.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The terminals."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public List<ShopTerminalOptionDto> terminals(@RequestParam String query) {
    return service.terminals(query);
  }

  /**
   * Sets, clears or releases a shop's terminal link by hand.
   *
   * @param id the shop
   * @param request the change and the link the admin saw
   * @return the shop after the change
   */
  @PutMapping("/{id}/terminal-link")
  @Operation(summary = "Set, clear or release a P4K shop's UEX terminal link by hand.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The shop."),
    @ApiResponse(responseCode = "400", description = "A link names no terminal."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "No shop or terminal with the given id."),
    @ApiResponse(responseCode = "409", description = "The shop's link changed since it was read.")
  })
  public ShopAdminDto changeLink(
      @PathVariable("id") UUID id, @RequestBody @Valid ShopTerminalLinkRequest request) {
    return service.changeLink(
        id,
        request.action(),
        request.terminalId(),
        request.expectedTerminalId(),
        request.expectedManual());
  }
}
