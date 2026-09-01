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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryAllocationDimension;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * Pure-Mockito unit tests for the three Variante-C allocation proxy endpoints of {@link
 * InventoryWriteController} (REQ-INV-027): each forwards to the canonical backend path with the
 * matching HTTP method and relays the backend status/body verbatim through {@code
 * propagateBackendError} — so the AJAX layer keeps its 409-reload vs 422-toast distinction (the
 * over-allocation case).
 */
@ExtendWith(MockitoExtension.class)
class InventoryWriteControllerAllocationTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private InventoryPageController inventoryPageController;

  private InventoryWriteController controller() {
    return new InventoryWriteController(backendApiClient, inventoryPageController);
  }

  private static InventoryItemDto sentinel() {
    return new InventoryItemDto(
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        750,
        10.0,
        false,
        List.of(),
        0.0,
        List.of(),
        0.0,
        null,
        null,
        3L,
        null,
        null);
  }

  @Test
  void addAllocation_forwardsPostToBackendAndReturnsDto() {
    UUID id = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 4.0, 1L);
    InventoryItemDto out = sentinel();
    when(backendApiClient.post(
            "/api/v1/inventory/" + id + "/allocation", dto, InventoryItemDto.class))
        .thenReturn(out);

    ResponseEntity<Object> result = controller().addAllocation(id, dto);

    assertEquals(200, result.getStatusCode().value());
    assertSame(out, result.getBody());
    verify(backendApiClient)
        .post("/api/v1/inventory/" + id + "/allocation", dto, InventoryItemDto.class);
  }

  @Test
  void addAllocation_onOverAllocation_propagates422() {
    UUID id = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), 99.0, 1L);
    when(backendApiClient.post(
            "/api/v1/inventory/" + id + "/allocation", dto, InventoryItemDto.class))
        .thenThrow(
            new BackendServiceException(
                "over", null, 422, "OVER_ALLOCATION", "corr-422", List.of(), null));

    ResponseEntity<Object> result = controller().addAllocation(id, dto);

    // propagateBackendError relays the backend status so krt-fetch.js can toast (not reload).
    assertEquals(422, result.getStatusCode().value());
  }

  @Test
  void changeAllocation_forwardsPatchToBackend() {
    UUID id = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.MISSION, UUID.randomUUID(), 6.0, 2L);
    InventoryItemDto out = sentinel();
    when(backendApiClient.patch(
            "/api/v1/inventory/" + id + "/allocation", dto, InventoryItemDto.class))
        .thenReturn(out);

    ResponseEntity<Object> result = controller().changeAllocation(id, dto);

    assertEquals(200, result.getStatusCode().value());
    assertSame(out, result.getBody());
    verify(backendApiClient)
        .patch("/api/v1/inventory/" + id + "/allocation", dto, InventoryItemDto.class);
  }

  @Test
  void removeAllocation_forwardsBodyCarryingDeleteToBackend() {
    UUID id = UUID.randomUUID();
    InventoryAllocationWriteDto dto =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, UUID.randomUUID(), null, 3L);
    InventoryItemDto out = sentinel();
    when(backendApiClient.delete(
            eq("/api/v1/inventory/" + id + "/allocation"), eq(dto), eq(InventoryItemDto.class)))
        .thenReturn(out);

    ResponseEntity<Object> result = controller().removeAllocation(id, dto);

    assertEquals(200, result.getStatusCode().value());
    assertSame(out, result.getBody());
    verify(backendApiClient)
        .delete("/api/v1/inventory/" + id + "/allocation", dto, InventoryItemDto.class);
  }
}
