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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.support.JobOrderInventoryOwnerRedactor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-method unit tests for {@link MaterialCollectionController}. The controller is a thin
 * delegation layer; the real sorting / aggregation logic lives in {@code
 * InventoryItemService.getMaterialCollection(...)} which has its own test coverage. Here we
 * guarantee delegation and the owner/location redaction wiring (REQ-ORDERS-029): a responsible-side
 * viewer gets the list unmodified, a requesting-side viewer ({@code canSeeJobOrderInventoryOwners
 * == false}) gets the redactor's output.
 */
@ExtendWith(MockitoExtension.class)
class MaterialCollectionControllerTest {

  @Mock private InventoryItemService inventoryItemService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private JobOrderInventoryOwnerRedactor inventoryOwnerRedactor;

  @InjectMocks private MaterialCollectionController controller;

  private static MaterialCollectionEntryDto sampleEntry() {
    return new MaterialCollectionEntryDto(
        UUID.randomUUID(),
        1L,
        "alice",
        UUID.randomUUID(),
        "Lorville",
        UUID.randomUUID(),
        "Gold",
        800.0,
        5.0,
        3.0,
        false);
  }

  @Test
  void getMaterialCollection_responsibleSideViewer_returnsListUnredacted() {
    // covers REQ-ORDERS-029
    UUID jobOrderId = UUID.randomUUID();
    List<MaterialCollectionEntryDto> expected = List.of(sampleEntry());
    when(inventoryItemService.getMaterialCollection(jobOrderId)).thenReturn(expected);
    when(ownerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)).thenReturn(true);

    List<MaterialCollectionEntryDto> result = controller.getMaterialCollection(jobOrderId);

    assertSame(expected, result, "controller must return the service's list unmodified");
    verify(inventoryItemService).getMaterialCollection(jobOrderId);
    verifyNoInteractions(inventoryOwnerRedactor);
  }

  @Test
  void getMaterialCollection_requestingSideViewer_returnsRedactedList() {
    // covers REQ-ORDERS-029
    UUID jobOrderId = UUID.randomUUID();
    List<MaterialCollectionEntryDto> raw = List.of(sampleEntry());
    List<MaterialCollectionEntryDto> redacted = List.of(sampleEntry());
    when(inventoryItemService.getMaterialCollection(jobOrderId)).thenReturn(raw);
    when(ownerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)).thenReturn(false);
    when(inventoryOwnerRedactor.redactMaterialCollection(raw)).thenReturn(redacted);

    List<MaterialCollectionEntryDto> result = controller.getMaterialCollection(jobOrderId);

    assertSame(redacted, result, "a requesting-side viewer must get the redacted list");
    verify(inventoryOwnerRedactor).redactMaterialCollection(raw);
  }

  @Test
  void getMaterialCollection_emptyResult_isReturnedAsIs() {
    UUID jobOrderId = UUID.randomUUID();
    when(inventoryItemService.getMaterialCollection(jobOrderId)).thenReturn(List.of());
    when(ownerScopeService.canSeeJobOrderInventoryOwners(jobOrderId)).thenReturn(true);

    List<MaterialCollectionEntryDto> result = controller.getMaterialCollection(jobOrderId);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
