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
 * InventoryItemService.getMaterialCollection(...)} which has its own test coverage. Here we just
 * guarantee delegation.
 */
@ExtendWith(MockitoExtension.class)
class MaterialCollectionControllerTest {

  @Mock private InventoryItemService inventoryItemService;

  @InjectMocks private MaterialCollectionController controller;

  @Test
  void getMaterialCollection_delegatesJobOrderIdToService_andReturnsList() {
    UUID jobOrderId = UUID.randomUUID();
    List<MaterialCollectionEntryDto> expected =
        List.of(
            new MaterialCollectionEntryDto(
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
                false));
    when(inventoryItemService.getMaterialCollection(jobOrderId)).thenReturn(expected);

    List<MaterialCollectionEntryDto> result = controller.getMaterialCollection(jobOrderId);

    assertSame(expected, result, "controller must return the service's list unmodified");
    verify(inventoryItemService).getMaterialCollection(jobOrderId);
    verifyNoMoreInteractions(inventoryItemService);
  }

  @Test
  void getMaterialCollection_emptyResult_isReturnedAsIs() {
    UUID jobOrderId = UUID.randomUUID();
    when(inventoryItemService.getMaterialCollection(jobOrderId)).thenReturn(List.of());

    List<MaterialCollectionEntryDto> result = controller.getMaterialCollection(jobOrderId);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
