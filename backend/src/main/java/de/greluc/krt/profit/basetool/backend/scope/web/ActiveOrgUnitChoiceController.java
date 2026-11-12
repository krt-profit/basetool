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

package de.greluc.krt.profit.basetool.backend.scope.web;

import de.greluc.krt.profit.basetool.backend.scope.internal.ActiveOrgUnitChoices;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling member's stored choice of the active org unit (REQ-ORG-031), restored by the web at
 * the start of a session and written on every switch. The caller's identity selects the row, so a
 * caller only ever reads or changes their own.
 */
@RestController
@RequestMapping("/api/v1/org-units/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ActiveOrgUnitChoiceController {

  private final ActiveOrgUnitChoices activeOrgUnitChoices;

  /**
   * Returns the caller's stored choice; a unit the caller may no longer select reads as {@code
   * null}, which means all of the caller's org units.
   *
   * @return the stored choice and its version
   */
  @NotNull
  @GetMapping("/active-choice")
  @Operation(summary = "The caller's stored choice of the active org unit")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "The stored choice"),
        @ApiResponse(responseCode = "401", description = "Authentication required")
      })
  public ActiveOrgUnitChoiceResponse getActiveChoice() {
    return ActiveOrgUnitChoiceResponse.of(activeOrgUnitChoices.currentChoice());
  }

  /**
   * Stores the caller's choice of the active org unit; a changed choice is counted, not audited.
   *
   * @param request the chosen org unit, {@code null} for all of the caller's org units, and the
   *     optional version
   * @return the stored choice and its new version
   */
  @NotNull
  @PutMapping("/active-choice")
  @Operation(summary = "Store the caller's choice of the active org unit")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "The stored choice"),
        @ApiResponse(responseCode = "400", description = "The org unit is not selectable"),
        @ApiResponse(responseCode = "409", description = "The given version is stale")
      })
  public ActiveOrgUnitChoiceResponse putActiveChoice(
      @NotNull @RequestBody @Valid ActiveOrgUnitChoiceRequest request) {
    return ActiveOrgUnitChoiceResponse.of(
        activeOrgUnitChoices.choose(request.orgUnitId(), request.version()));
  }

  /**
   * The stored choice of the active org unit.
   *
   * @param orgUnitId the chosen org unit, or {@code null} for all of the caller's org units
   * @param version the row version to echo back, 0 while nothing is stored
   */
  public record ActiveOrgUnitChoiceResponse(@Nullable UUID orgUnitId, long version) {

    /**
     * Maps the service's snapshot.
     *
     * @param snapshot the stored choice
     * @return the response
     */
    static @NotNull ActiveOrgUnitChoiceResponse of(
        @NotNull ActiveOrgUnitChoices.Snapshot snapshot) {
      return new ActiveOrgUnitChoiceResponse(snapshot.orgUnitId(), snapshot.version());
    }
  }

  /**
   * A new choice of the active org unit.
   *
   * @param orgUnitId the chosen org unit, or {@code null} for all of the caller's org units
   * @param version the version the caller last read, or {@code null}: the switcher sends none, so
   *     the latest choice wins
   */
  public record ActiveOrgUnitChoiceRequest(@Nullable UUID orgUnitId, @Nullable Long version) {}
}
