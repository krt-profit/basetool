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

import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderStockReadService;
import de.greluc.krt.profit.basetool.backend.joborder.web.MaterialCollectionController;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MaterialCollectionController}: delegation with owner and location passed
 * through to every viewer of the order (REQ-ORDERS-029).
 */
@ExtendWith(MockitoExtension.class)
class MaterialCollectionControllerTest {

  @Mock private JobOrderStockReadService jobOrderStockReadService;

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
  void getMaterialCollection_returnsListWithOwnerAndLocationUnchanged() {
    UUID jobOrderId = UUID.randomUUID();
    List<MaterialCollectionEntryDto> expected = List.of(sampleEntry());
    when(jobOrderStockReadService.getMaterialCollection(jobOrderId)).thenReturn(expected);

    List<MaterialCollectionEntryDto> result = controller.getMaterialCollection(jobOrderId);

    assertSame(expected, result, "controller must return the service's list unmodified");
    assertEquals("alice", result.getFirst().ownerName());
    assertEquals("Lorville", result.getFirst().location());
  }

  @Test
  void getMaterialCollection_emptyResult_isReturnedAsIs() {
    UUID jobOrderId = UUID.randomUUID();
    when(jobOrderStockReadService.getMaterialCollection(jobOrderId)).thenReturn(List.of());

    List<MaterialCollectionEntryDto> result = controller.getMaterialCollection(jobOrderId);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
