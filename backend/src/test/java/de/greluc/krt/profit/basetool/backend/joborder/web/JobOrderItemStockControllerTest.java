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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemStockEntryDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderStockReadService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link JobOrderItemStockController} (REQ-ORDERS-028/029): delegation with owner
 * and location passed through to every viewer of the order.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemStockControllerTest {

  @Mock private JobOrderStockReadService jobOrderStockReadService;

  @InjectMocks private JobOrderItemStockController controller;

  private static JobOrderItemStockGroupDto sampleGroup() {
    return new JobOrderItemStockGroupDto(
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
                false)));
  }

  @Test
  void getItemStock_returnsListWithOwnerAndLocationUnchanged() {
    UUID jobOrderId = UUID.randomUUID();
    List<JobOrderItemStockGroupDto> expected = List.of(sampleGroup());
    when(jobOrderStockReadService.getItemStockForJobOrder(jobOrderId)).thenReturn(expected);

    List<JobOrderItemStockGroupDto> result = controller.getItemStock(jobOrderId);

    assertSame(expected, result, "controller must return the service's list unmodified");
    JobOrderItemStockEntryDto entry = result.getFirst().entries().getFirst();
    assertEquals("alice", entry.ownerName());
    assertEquals("Lorville", entry.location());
  }

  @Test
  void getItemStock_emptyResult_isReturnedAsIs() {
    UUID jobOrderId = UUID.randomUUID();
    when(jobOrderStockReadService.getItemStockForJobOrder(jobOrderId)).thenReturn(List.of());

    List<JobOrderItemStockGroupDto> result = controller.getItemStock(jobOrderId);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
