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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.model.dto.BulkStolenMarkRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkStolenMarkResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX proxies that set or remove the „gestohlen" marker on Lager rows from „Mein Lager"
 * (REQ-INV-053), for one row or a part of it and for a selection, relaying the backend status and
 * RFC 7807 {@code code}.
 */
@RestController
@RequestMapping("/inventory")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class InventoryStolenMarkProxyController {

  private final BackendApiClient backendApiClient;

  /**
   * Forwards the marker change of one row, or of a part of it, to {@code POST
   * /api/v1/inventory/{id}/stolen}; a request without the requested marker is refused here with a
   * {@code 422} {@code problem+json}.
   *
   * @param id the row
   * @param dto the version, the requested marker and the amount, {@code null} for the whole row
   * @return {@code 200} with the resulting row, or the propagated backend error
   */
  @PostMapping("/{id}/stolen")
  public ResponseEntity<Object> markStolen(
      @PathVariable @NotNull UUID id, @RequestBody InventoryItemStolenMarkDto dto) {
    if (dto == null || dto.stolen() == null) {
      return validationError();
    }
    try {
      InventoryItemDto result =
          backendApiClient.post("/api/v1/inventory/{id}/stolen", dto, InventoryItemDto.class, id);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      log.debug(
          "Failed to change the stolen marker of an inventory item: status={}, {}",
          e.getStatusCode(),
          e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to change the stolen marker of an inventory item", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Forwards the marker change of a selection to {@code POST /api/v1/inventory/bulk-stolen}; an
   * empty selection or a missing marker is refused here with a {@code 422} {@code problem+json}.
   *
   * @param request the selection and the requested marker
   * @return {@code 200} with the changed/skipped counts, otherwise the propagated backend error
   */
  @PostMapping("/bulk-stolen")
  public ResponseEntity<Object> bulkMarkStolen(@RequestBody BulkStolenMarkRequest request) {
    if (request == null
        || request.itemIds() == null
        || request.itemIds().isEmpty()
        || request.stolen() == null) {
      return validationError();
    }
    try {
      BulkStolenMarkResultDto result =
          backendApiClient.post(
              "/api/v1/inventory/bulk-stolen", request, BulkStolenMarkResultDto.class);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      log.debug("Failed to bulk-change the stolen marker: {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to bulk-change the stolen marker", e);
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
