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

package de.greluc.krt.profit.basetool.backend.inventory.web;

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.MemberSaleValueSettings;
import de.greluc.krt.profit.basetool.backend.inventory.internal.MySalePriceBasisRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.MySalePriceBasisResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling member's "Mein Lager" price basis, the profile's choice between the average, the
 * highest and the lowest terminal price (REQ-INV-072). The JWT identifies the row, so a caller only
 * ever reads or changes their own; the version is the setting's own optimistic-lock counter.
 */
@RestController
@RequestMapping("/api/v1/inventory/my-inventory/sale-price-basis")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MySalePriceBasisController {

  private final UserService userService;
  private final MemberSaleValueSettings memberSaleValueSettings;

  /**
   * Returns the caller's price basis and the version to echo back.
   *
   * @param jwt the caller's token
   * @return the basis in effect, the average price without a choice, and the version
   */
  @Operation(
      summary = "Read the own Mein Lager price basis",
      description =
          "The terminal price the caller's Mein Lager values the own materials at: AVERAGE (the"
              + " default), HIGHEST or LOWEST, with the setting's optimistic-lock version.")
  @GetMapping
  public MySalePriceBasisResponse getMySalePriceBasis(@AuthenticationPrincipal Jwt jwt) {
    return MySalePriceBasisResponse.of(
        memberSaleValueSettings.snapshotOf(userService.getUserIdFromJwt(jwt)));
  }

  /**
   * Sets the caller's price basis.
   *
   * @param jwt the caller's token
   * @param request the new basis and the version the caller last read
   * @return the stored basis and the new version
   */
  @Operation(summary = "Set the own Mein Lager price basis")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Set"),
    @ApiResponse(responseCode = "400", description = "Missing basis or version"),
    @ApiResponse(responseCode = "409", description = "Stale version")
  })
  @PutMapping
  public MySalePriceBasisResponse updateMySalePriceBasis(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody @Valid @NotNull MySalePriceBasisRequest request) {
    return MySalePriceBasisResponse.of(
        memberSaleValueSettings.update(
            userService.getUserIdFromJwt(jwt), request.salePriceBasis(), request.version()));
  }
}
