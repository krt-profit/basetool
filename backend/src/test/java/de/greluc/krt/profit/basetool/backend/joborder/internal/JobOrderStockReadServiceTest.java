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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStockReads;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.dto.*;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies the Lager reads of a job order: its material collection and its item stock. */
@ExtendWith(MockitoExtension.class)
class JobOrderStockReadServiceTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;

  private JobOrderStockReadService service;

  /** Builds the service over the mocks. */
  @BeforeEach
  void setUp() {
    service =
        new JobOrderStockReadService(
            new InventoryStockReads(inventoryItemRepository, inventoryItemMapper),
            jobOrderRepository);
  }

  @Test
  void getMaterialCollection_shouldReturnMappedEntries() {
    UUID jobOrderId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();

    JobOrder jobOrder = new JobOrder();
    jobOrder.setId(jobOrderId);

    User user = new User();
    user.setId(userId);
    user.setUsername("testuser");
    user.setDisplayName("Test User");

    Location location = new Location();
    location.setId(locationId);
    location.setName("Port Olisar");

    Material material = new Material();
    material.setName("Laranite");

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(user);
    item.setLocation(location);
    item.setMaterial(material);
    item.setQuality(100);
    item.setAmount(5.0);
    InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
    slice.setInventoryItem(item);
    slice.setJobOrderId(jobOrder.getId());
    slice.setAmount(3.0);
    slice.setDelivered(true);
    item.getJobOrderAllocations().add(slice);

    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findByJobOrderIdOrdered(jobOrderId)).thenReturn(List.of(item));

    List<MaterialCollectionEntryDto> result = service.getMaterialCollection(jobOrderId);

    assertEquals(1, result.size());
    MaterialCollectionEntryDto dto = result.get(0);
    assertEquals(itemId, dto.inventoryEntryId());
    assertEquals(1L, dto.version());
    assertEquals("Test User", dto.ownerName());
    assertEquals(userId, dto.ownerId());
    assertEquals("Port Olisar", dto.location());
    assertEquals(locationId, dto.locationId());
    assertEquals("Laranite", dto.materialName());
    assertEquals(5.0, dto.quantity(), "quantity is the entry's total physical stock");
    assertEquals(
        3.0, dto.allocatedQuantity(), "allocatedQuantity is this order's earmarked slice, not 5");
    assertTrue(dto.delivered(), "delivered reads the order's slice, not the entry scalar");
  }

  @Test
  void getMaterialCollection_shouldThrowWhenJobOrderNotFound() {
    UUID jobOrderId = UUID.randomUUID();
    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.getMaterialCollection(jobOrderId));
  }

  /**
   * Builds a game-item inventory row with one earmark slice per (order, amount, delivered) triple,
   * for the Item-Bestand grouping tests.
   */
  private static InventoryItem itemStockRow(
      GameItem gameItem,
      User owner,
      Location location,
      double amount,
      Long version,
      Object[][] slices) {
    InventoryItem row = new InventoryItem();
    row.setId(UUID.randomUUID());
    row.setVersion(version);
    row.setUser(owner);
    row.setLocation(location);
    row.setGameItem(gameItem);
    row.setAmount(amount);
    for (Object[] sliceSpec : slices) {
      InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
      slice.setInventoryItem(row);
      slice.setJobOrderId(((JobOrder) sliceSpec[0]).getId());
      slice.setAmount((Double) sliceSpec[1]);
      slice.setDelivered((Boolean) sliceSpec[2]);
      row.getJobOrderAllocations().add(slice);
    }
    return row;
  }

  @Test
  void getItemStockForJobOrder_groupsPerGameItemAndReadsTheOrderSlice() {
    UUID jobOrderId = UUID.randomUUID();
    JobOrder jobOrder = new JobOrder();
    jobOrder.setId(jobOrderId);
    JobOrder foreignOrder = new JobOrder();
    foreignOrder.setId(UUID.randomUUID());

    GameItem rifle = new GameItem();
    rifle.setId(UUID.randomUUID());
    rifle.setName("Rifle");
    GameItem scope = new GameItem();
    scope.setId(UUID.randomUUID());
    scope.setName("Optic Scope");

    JobOrderItem rifleLineA = new JobOrderItem();
    rifleLineA.setGameItem(rifle);
    rifleLineA.setAmount(3);
    rifleLineA.setManufacturedAmount(2);
    JobOrderItem rifleLineB = new JobOrderItem();
    rifleLineB.setGameItem(rifle);
    rifleLineB.setAmount(2);
    rifleLineB.setManufacturedAmount(0);
    JobOrderItem scopeLine = new JobOrderItem();
    scopeLine.setGameItem(scope);
    scopeLine.setAmount(1);
    scopeLine.setManufacturedAmount(0);
    jobOrder.addItem(rifleLineA);
    jobOrder.addItem(rifleLineB);
    jobOrder.addItem(scopeLine);

    User alice = new User();
    alice.setId(UUID.randomUUID());
    alice.setUsername("alice");
    alice.setDisplayName("Alice");
    Location lorville = new Location();
    lorville.setId(UUID.randomUUID());
    lorville.setName("Lorville");

    InventoryItem scopeRow =
        itemStockRow(scope, alice, lorville, 1.0, 3L, new Object[][] {{jobOrder, 1.0, false}});
    InventoryItem rifleRowShared =
        itemStockRow(
            rifle,
            alice,
            lorville,
            4.0,
            7L,
            new Object[][] {{foreignOrder, 2.0, false}, {jobOrder, 1.0, true}});
    InventoryItem rifleRowPlain =
        itemStockRow(rifle, alice, lorville, 2.0, null, new Object[][] {{jobOrder, 2.0, false}});

    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findGameItemRowsByJobOrderIdOrdered(jobOrderId))
        .thenReturn(List.of(scopeRow, rifleRowShared, rifleRowPlain));
    when(inventoryItemMapper.gameItemToReferenceDto(any(GameItem.class)))
        .thenAnswer(
            inv -> {
              GameItem gi = inv.getArgument(0);
              return new InventoryGameItemReferenceDto(gi.getId(), gi.getName(), null, null);
            });

    List<JobOrderItemStockGroupDto> result = service.getItemStockForJobOrder(jobOrderId);

    assertEquals(2, result.size());
    JobOrderItemStockGroupDto scopeGroup = result.get(0);
    JobOrderItemStockGroupDto rifleGroup = result.get(1);
    assertEquals("Optic Scope", scopeGroup.gameItem().name(), "groups are name-sorted");
    assertEquals("Rifle", rifleGroup.gameItem().name());

    assertEquals(5, rifleGroup.orderedAmount(), "ordered = 3 + 2 across the two rifle lines");
    assertEquals(2, rifleGroup.manufacturedAmount());
    assertEquals(
        3L,
        rifleGroup.allocatedTotal(),
        "Σ of THIS order's slices (1 + 2), not the 2.0" + " earmarked to the sibling order");
    assertEquals(2, rifleGroup.entries().size());

    JobOrderItemStockEntryDto shared = rifleGroup.entries().get(0);
    assertEquals(rifleRowShared.getId(), shared.inventoryEntryId());
    assertEquals(7L, shared.version());
    assertEquals("Alice", shared.ownerName());
    assertEquals(alice.getId(), shared.ownerId());
    assertEquals("Lorville", shared.location());
    assertEquals(lorville.getId(), shared.locationId());
    assertEquals(4L, shared.quantity(), "quantity is the entry's total physical stock");
    assertEquals(1L, shared.allocatedQuantity(), "allocatedQuantity is this order's slice");
    assertTrue(shared.delivered(), "delivered reads this order's slice, not the sibling's");

    assertEquals(0L, rifleGroup.entries().get(1).version());
    assertFalse(rifleGroup.entries().get(1).delivered());

    assertEquals(1, scopeGroup.orderedAmount());
    assertEquals(0, scopeGroup.manufacturedAmount());
    assertEquals(1L, scopeGroup.allocatedTotal());
  }

  @Test
  void getItemStockForJobOrder_orphanedEarmark_hasZeroLineContext() {
    UUID jobOrderId = UUID.randomUUID();
    JobOrder jobOrder = new JobOrder();
    jobOrder.setId(jobOrderId);

    GameItem helmet = new GameItem();
    helmet.setId(UUID.randomUUID());
    helmet.setName("Helmet");
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("bob");
    Location loc = new Location();
    loc.setId(UUID.randomUUID());
    loc.setName("Area18");

    InventoryItem row =
        itemStockRow(helmet, owner, loc, 2.0, 1L, new Object[][] {{jobOrder, 2.0, false}});

    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.of(jobOrder));
    when(inventoryItemRepository.findGameItemRowsByJobOrderIdOrdered(jobOrderId))
        .thenReturn(List.of(row));
    when(inventoryItemMapper.gameItemToReferenceDto(any(GameItem.class)))
        .thenAnswer(
            inv -> {
              GameItem gi = inv.getArgument(0);
              return new InventoryGameItemReferenceDto(gi.getId(), gi.getName(), null, null);
            });

    List<JobOrderItemStockGroupDto> result = service.getItemStockForJobOrder(jobOrderId);

    assertEquals(1, result.size());
    assertEquals(0, result.get(0).orderedAmount());
    assertEquals(0, result.get(0).manufacturedAmount());
    assertEquals(2L, result.get(0).allocatedTotal());
    assertEquals("bob", result.get(0).entries().get(0).ownerName(), "username fallback");
  }

  @Test
  void getItemStockForJobOrder_shouldThrowWhenJobOrderNotFound() {
    UUID jobOrderId = UUID.randomUUID();
    when(jobOrderRepository.findById(jobOrderId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.getItemStockForJobOrder(jobOrderId));
  }
}
