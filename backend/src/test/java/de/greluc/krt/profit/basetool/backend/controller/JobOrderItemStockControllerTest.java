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

import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-method unit tests for {@link JobOrderItemStockController} (REQ-ORDERS-028). The controller
 * is a thin delegation layer mirroring {@link MaterialCollectionControllerTest}; the grouping /
 * slice logic lives in {@code InventoryAggregationService.getItemStockForJobOrder(...)} and is
 * covered there. Here we just guarantee delegation.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemStockControllerTest {

  @Mock private InventoryItemService inventoryItemService;

  @InjectMocks private JobOrderItemStockController controller;

  @Test
  void getItemStock_delegatesJobOrderIdToService_andReturnsList() {
    // covers REQ-ORDERS-028
    UUID jobOrderId = UUID.randomUUID();
    List<JobOrderItemStockGroupDto> expected =
        List.of(
            new JobOrderItemStockGroupDto(
                new InventoryGameItemReferenceDto(
                    UUID.randomUUID(), "A03 Sniper Rifle", "Behring", "WEAPON"),
                3,
                1,
                1L,
                List.of(
                    new JobOrderItemStockEntryDto(
                        UUID.randomUUID(),
                        2L,
                        "alice",
                        UUID.randomUUID(),
                        "Lorville",
                        UUID.randomUUID(),
                        4L,
                        1L,
                        false))));
    when(inventoryItemService.getItemStockForJobOrder(jobOrderId)).thenReturn(expected);

    List<JobOrderItemStockGroupDto> result = controller.getItemStock(jobOrderId);

    assertSame(expected, result, "controller must return the service's list unmodified");
    verify(inventoryItemService).getItemStockForJobOrder(jobOrderId);
    verifyNoMoreInteractions(inventoryItemService);
  }

  @Test
  void getItemStock_emptyResult_isReturnedAsIs() {
    // covers REQ-ORDERS-028
    UUID jobOrderId = UUID.randomUUID();
    when(inventoryItemService.getItemStockForJobOrder(jobOrderId)).thenReturn(List.of());

    List<JobOrderItemStockGroupDto> result = controller.getItemStock(jobOrderId);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
