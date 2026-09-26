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

import de.greluc.krt.profit.basetool.frontend.model.dto.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkOrgUnitChangeResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemOrgUnitChangeDto;
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
 * AJAX proxies that change the org unit of the caller's own personal Lager rows from „Mein Lager"
 * (REQ-INV-052), singly and for a selection, relaying the backend status and RFC 7807 {@code code}.
 */
@RestController
@RequestMapping("/inventory")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class InventoryOrgUnitChangeProxyController {

  private final BackendApiClient backendApiClient;

  /**
   * Forwards the org-unit change of one personal row to {@code POST
   * /api/v1/inventory/{id}/org-unit}.
   *
   * @param id the row
   * @param dto the version, the target unit or {@code null}, and the merge opt-in
   * @return {@code 200} with the resulting row, or the propagated backend error
   */
  @PostMapping("/{id}/org-unit")
  public ResponseEntity<Object> changeOrgUnit(
      @PathVariable @NotNull UUID id, @RequestBody InventoryItemOrgUnitChangeDto dto) {
    if (dto == null) {
      return validationError();
    }
    try {
      InventoryItemDto result =
          backendApiClient.post(
              "/api/v1/inventory/" + id + "/org-unit", dto, InventoryItemDto.class);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      log.debug(
          "Failed to change inventory item org unit: status={}, {}",
          e.getStatusCode(),
          e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to change inventory item org unit", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Forwards the org-unit change of a selection to {@code POST /api/v1/inventory/bulk-org-unit}; an
   * empty selection is refused here with a {@code 422} {@code problem+json}.
   *
   * @param request the selection, the target unit or {@code null}, and the merge opt-in
   * @return {@code 200} with the changed/skipped counts, otherwise the propagated backend error
   */
  @PostMapping("/bulk-org-unit")
  public ResponseEntity<Object> bulkChangeOrgUnit(@RequestBody BulkOrgUnitChangeRequest request) {
    if (request == null || request.itemIds() == null || request.itemIds().isEmpty()) {
      return validationError();
    }
    try {
      BulkOrgUnitChangeResultDto result =
          backendApiClient.post(
              "/api/v1/inventory/bulk-org-unit", request, BulkOrgUnitChangeResultDto.class);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      log.debug("Failed to bulk-change inventory org units: {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to bulk-change inventory org units", e);
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
