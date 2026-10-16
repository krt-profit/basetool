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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.inventory.client.InventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemPoolChangeDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.OrgUnitReferenceDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX proxies of the Globales Lager that move a shared row, or a part of it, into another pool of
 * its holder (REQ-INV-059) and list the pools it may move into, relaying the backend status and RFC
 * 7807 {@code code}.
 */
@RestController
@RequestMapping("/inventory")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class InventoryPoolChangeProxyController {

  /** The inventory domain's typed backend client. */
  private final InventoryBackendClient inventoryClient;

  /**
   * Forwards the pool change of one shared row to {@code POST /api/v1/inventory/{id}/pool}.
   *
   * @param id the row
   * @param dto the version, the target unit, the part and the merge opt-in
   * @return {@code 200} with the resulting row, a {@code 422} for a request without target or
   *     version, or the propagated backend error
   */
  @PostMapping("/{id}/pool")
  public ResponseEntity<Object> changePool(
      @PathVariable @NotNull UUID id, @RequestBody InventoryItemPoolChangeDto dto) {
    if (dto == null || dto.targetOwningOrgUnitId() == null || dto.version() == null) {
      return validationError();
    }
    try {
      InventoryItemDto result = inventoryClient.changePool(id, dto);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      log.debug(
          "Failed to move inventory item into another pool: status={}, {}",
          e.getStatusCode(),
          e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to move inventory item into another pool", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Relays the pools a shared row may move into from {@code GET
   * /api/v1/inventory/{id}/pool-targets}.
   *
   * @param id the row
   * @return {@code 200} with the units, or the propagated backend error
   */
  @GetMapping("/{id}/pool-targets")
  public ResponseEntity<Object> poolTargets(@PathVariable @NotNull UUID id) {
    try {
      List<OrgUnitReferenceDto> targets = inventoryClient.poolTargets(id);
      return ResponseEntity.ok(Objects.requireNonNullElse(targets, List.of()));
    } catch (BackendServiceException e) {
      log.debug("Failed to list pool targets: status={}, {}", e.getStatusCode(), e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to list pool targets", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Builds the {@code 422} {@code problem+json} the inventory pages show as an inline toast.
   *
   * @return the response carrying the {@code VALIDATION} code
   */
  private static @NotNull ResponseEntity<Object> validationError() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", 422);
    body.put("code", "VALIDATION");
    return ResponseEntity.unprocessableContent()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
