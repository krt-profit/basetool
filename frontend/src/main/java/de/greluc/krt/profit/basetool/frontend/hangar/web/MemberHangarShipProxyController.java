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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.hangar.client.HangarBackendClient;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipRequestDto;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend proxy of the admin member hangar's writes (REQ-HANGAR-011): add, edit and delete a ship
 * of one member through the backend's admin hangar paths, each answering {@code 204} for an
 * in-place list swap.
 */
@Slf4j
@RestController
@RequestMapping("/admin/members/{userId}/hangar")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class MemberHangarShipProxyController {

  /** Sends the ship writes to the member's admin paths. */
  private final HangarBackendClient hangarClient;

  /**
   * Adds a ship to the member's hangar; the owner picker's unit is passed on, the version never.
   *
   * @param userId the member
   * @param request the ship payload as JSON
   * @return {@code 204} on success, {@code 422} with code {@code VALIDATION} on a missing required
   *     field, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/add", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> addShip(
      @PathVariable @NotNull UUID userId, @RequestBody @Nullable ShipRequestDto request) {
    if (isIncomplete(request)) {
      return validationError();
    }
    return relay(
        log,
        "add member ship (ajax)",
        () -> {
          hangarClient.createMemberShip(
              userId,
              new ShipRequestDto(
                  request.name(),
                  request.shipTypeId(),
                  request.insurance(),
                  request.locationId(),
                  request.fitted(),
                  null,
                  request.owningOrgUnitId()));
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Updates a ship of the member's hangar with the last-seen version; the org unit is not changed.
   *
   * @param userId the member
   * @param shipId the ship
   * @param request the ship payload as JSON
   * @return {@code 204} on success, {@code 422} with code {@code VALIDATION} on a missing required
   *     field, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/{shipId}/update", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateShip(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID shipId,
      @RequestBody @Nullable ShipRequestDto request) {
    if (isIncomplete(request)) {
      return validationError();
    }
    return relay(
        log,
        "update member ship (ajax)",
        () -> {
          hangarClient.updateMemberShip(
              userId,
              shipId,
              new ShipRequestDto(
                  request.name(),
                  request.shipTypeId(),
                  request.insurance(),
                  request.locationId(),
                  request.fitted(),
                  request.version(),
                  null));
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Deletes a ship of the member's hangar.
   *
   * @param userId the member
   * @param shipId the ship
   * @return {@code 204} on success, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/{shipId}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteShip(
      @PathVariable @NotNull UUID userId, @PathVariable @NotNull UUID shipId) {
    return relay(
        log,
        "delete member ship (ajax)",
        () -> {
          hangarClient.deleteMemberShip(userId, shipId);
          return ResponseEntity.noContent().build();
        });
  }

  /**
   * Tells whether a ship payload lacks a field the backend requires.
   *
   * @param request the payload, or {@code null}
   * @return {@code true} without a payload, a ship type or an insurance value
   */
  @Contract("null -> true")
  private static boolean isIncomplete(@Nullable ShipRequestDto request) {
    return request == null
        || request.shipTypeId() == null
        || request.insurance() == null
        || request.insurance().isBlank();
  }

  /**
   * Builds the {@code 422} {@code problem+json} with the {@code VALIDATION} code the page shows as
   * a toast.
   *
   * @return a {@code 422} {@code problem+json} response
   */
  private static @NotNull ResponseEntity<Object> validationError() {
    return ResponseEntity.status(422)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(Map.of("status", 422, "code", "VALIDATION"));
  }
}
