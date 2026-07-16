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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the write-time stock merge {@link InventoryCheckoutService#mergeStockIfRequested}
 * (REQ-INV-026): a {@code PIECE} write folds matching rows automatically, an {@code SCU} write only
 * on the per-action opt-in, and a row backing a Materialbörse offer is never merged.
 *
 * <p>Under the Variante-C model (REQ-INV-027) the merge key is the physical identity alone (owner ·
 * material · location · quality · personal · owning org unit), so rows that differ only in their
 * job-order / mission earmark now fold together; the survivor unions the victims' {@link
 * InventoryJobOrderAllocation} / mission slices — summing per target id and OR-combining a
 * job-order slice's delivered marker — rather than being reset to a scalar not-delivered.
 */
@ExtendWith(MockitoExtension.class)
class InventoryStockMergeTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuditService auditService;
  @InjectMocks private InventoryCheckoutService service;

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID MATERIAL_ID = UUID.randomUUID();
  private static final UUID LOCATION_ID = UUID.randomUUID();

  /**
   * Builds a managed-like inventory row sharing the fixed stock identity used across these tests
   * (owner {@link #USER_ID}, material {@link #MATERIAL_ID}, location {@link #LOCATION_ID}, quality
   * 100, non-personal, no owning org unit). The row starts with empty allocation collections — a
   * test that needs an earmark appends a slice through {@link InventoryAllocations} after the row
   * is built.
   *
   * @param id the row id.
   * @param quantityType the material's quantity type.
   * @param amount the row's amount.
   * @param note the row's note (may be {@code null}).
   * @return the assembled row.
   */
  private static InventoryItem row(UUID id, QuantityType quantityType, double amount, String note) {
    User user = new User();
    user.setId(USER_ID);
    Material material = new Material();
    material.setId(MATERIAL_ID);
    material.setQuantityType(quantityType);
    Location location = new Location();
    location.setId(LOCATION_ID);
    InventoryItem item = new InventoryItem();
    item.setId(id);
    item.setUser(user);
    item.setMaterial(material);
    item.setLocation(location);
    item.setQuality(100);
    item.setPersonal(false);
    item.setAmount(amount);
    item.setNote(note);
    return item;
  }

  /**
   * Builds a bare {@link JobOrder} carrying only {@code id}, enough for the merge's allocation
   * union to match slices by job-order id.
   *
   * @param id the job-order id.
   * @return the assembled job order.
   */
  private static JobOrder jobOrder(UUID id) {
    JobOrder order = new JobOrder();
    order.setId(id);
    return order;
  }

  @Test
  void pieceMaterial_foldsSiblings_sumsAmounts_mergesDistinctNotes_unionsAllocations() {
    UUID survivorId = UUID.randomUUID();
    JobOrder order = jobOrder(UUID.randomUUID());
    InventoryItem survivor = row(survivorId, QuantityType.PIECE, 5.0, "note-a");
    InventoryAllocations.addJobOrder(survivor, order, 5.0, false);
    InventoryItem victim1 = row(UUID.randomUUID(), QuantityType.PIECE, 3.0, "note-b");
    InventoryItem victim2 = row(UUID.randomUUID(), QuantityType.PIECE, 2.0, "note-a");
    InventoryAllocations.addJobOrder(victim2, order, 2.0, true);

    when(materialExchangeOfferRepository.existsByInventoryItemId(survivorId)).thenReturn(false);
    when(inventoryItemRepository.findMergeGroupForUpdate(
            USER_ID, MATERIAL_ID, LOCATION_ID, 100, false, null))
        .thenReturn(List.of(survivor, victim1, victim2));
    when(inventoryItemRepository.saveAndFlush(survivor)).thenReturn(survivor);

    InventoryItem result = service.mergeStockIfRequested(survivor, false);

    assertSame(survivor, result, "the survivor is the passed-in row");
    assertEquals(10.0, survivor.getAmount(), 1e-9, "amounts of all folded rows are summed");
    assertEquals("note-a\nnote-b", survivor.getNote(), "distinct notes are concatenated in order");

    // The victims' job-order slices union into the survivor: victim2 targets the same order, so
    // its amount sums into the survivor's slice and the delivered marker is OR-combined (delivered
    // because victim2's part was), rather than the survivor being reset to a scalar not-delivered.
    assertEquals(1, survivor.getJobOrderAllocations().size(), "same-order slices fold into one");
    InventoryJobOrderAllocation slice = survivor.getJobOrderAllocations().get(0);
    assertSame(order, slice.getJobOrder(), "the surviving slice keeps the shared job order");
    assertEquals(7.0, slice.getAmount(), 1e-9, "the shared order's slice amount is summed");
    assertTrue(slice.getDelivered(), "delivered is OR-combined across the folded slices");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<InventoryItem>> deleted = ArgumentCaptor.forClass(List.class);
    verify(inventoryItemRepository).deleteAll(deleted.capture());
    assertEquals(
        List.of(victim1, victim2), deleted.getValue(), "every non-survivor row is deleted");
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_ITEM_MERGED), eq(survivorId), any(), eq(USER_ID), any());
  }

  @Test
  void scuMaterial_withoutOptIn_isNoOp() {
    InventoryItem row = row(UUID.randomUUID(), QuantityType.SCU, 5.0, "n");

    InventoryItem result = service.mergeStockIfRequested(row, false);

    assertSame(row, result);
    assertEquals(5.0, row.getAmount(), 1e-9, "the amount is untouched");
    verifyNoInteractions(inventoryItemRepository, materialExchangeOfferRepository, auditService);
  }

  @Test
  void scuMaterial_withOptIn_merges() {
    UUID survivorId = UUID.randomUUID();
    InventoryItem survivor = row(survivorId, QuantityType.SCU, 5.0, "x");
    InventoryItem victim = row(UUID.randomUUID(), QuantityType.SCU, 2.0, "y");

    when(materialExchangeOfferRepository.existsByInventoryItemId(survivorId)).thenReturn(false);
    when(inventoryItemRepository.findMergeGroupForUpdate(
            USER_ID, MATERIAL_ID, LOCATION_ID, 100, false, null))
        .thenReturn(List.of(survivor, victim));
    when(inventoryItemRepository.saveAndFlush(survivor)).thenReturn(survivor);

    InventoryItem result = service.mergeStockIfRequested(survivor, true);

    assertSame(survivor, result);
    assertEquals(7.0, survivor.getAmount(), 1e-9);
    assertEquals("x\ny", survivor.getNote());
    verify(inventoryItemRepository).deleteAll(any());
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_ITEM_MERGED), eq(survivorId), any(), eq(USER_ID), any());
  }

  @Test
  void offerBackedRow_isNeverMerged() {
    UUID rowId = UUID.randomUUID();
    InventoryItem row = row(rowId, QuantityType.PIECE, 5.0, "n");

    when(materialExchangeOfferRepository.existsByInventoryItemId(rowId)).thenReturn(true);

    InventoryItem result = service.mergeStockIfRequested(row, false);

    assertSame(row, result);
    assertEquals(5.0, row.getAmount(), 1e-9, "an offer-backed row's amount is untouched");
    verify(inventoryItemRepository, never())
        .findMergeGroupForUpdate(any(), any(), any(), any(), any(), any());
    verifyNoInteractions(auditService);
  }

  @Test
  void noMatchingSibling_isNoOp() {
    UUID rowId = UUID.randomUUID();
    InventoryItem row = row(rowId, QuantityType.PIECE, 5.0, "n");

    when(materialExchangeOfferRepository.existsByInventoryItemId(rowId)).thenReturn(false);
    when(inventoryItemRepository.findMergeGroupForUpdate(
            USER_ID, MATERIAL_ID, LOCATION_ID, 100, false, null))
        .thenReturn(List.of(row));

    InventoryItem result = service.mergeStockIfRequested(row, false);

    assertSame(row, result);
    assertEquals(5.0, row.getAmount(), 1e-9);
    verify(inventoryItemRepository, never()).deleteAll(any());
    verify(inventoryItemRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditService);
  }

  /**
   * Builds a game-item stock row sharing the fixed identity dimensions of {@link #row}, but with
   * the item catalog shape (V220, REQ-INV-029): gameItem set, material and quality {@code null}.
   *
   * @param id the row id.
   * @param gameItem the stocked game item.
   * @param amount the row's amount.
   * @return the assembled item row.
   */
  private static InventoryItem itemRow(UUID id, GameItem gameItem, double amount) {
    User user = new User();
    user.setId(USER_ID);
    Location location = new Location();
    location.setId(LOCATION_ID);
    InventoryItem item = new InventoryItem();
    item.setId(id);
    item.setUser(user);
    item.setGameItem(gameItem);
    item.setLocation(location);
    item.setPersonal(false);
    item.setAmount(amount);
    return item;
  }

  // covers REQ-INV-029 (item rows always auto-merge, keyed through the 7-arg NULL-branch query)
  @Test
  void gameItemRow_alwaysAutoMerges_viaSevenArgumentNullBranchQuery() {
    // Given a fresh item row and one matching sibling behind the catalog-discriminated merge key
    GameItem gameItem = new GameItem();
    gameItem.setId(UUID.randomUUID());
    gameItem.setName("Quantum Drive");
    UUID survivorId = UUID.randomUUID();
    InventoryItem survivor = itemRow(survivorId, gameItem, 3.0);
    InventoryItem victim = itemRow(UUID.randomUUID(), gameItem, 2.0);
    when(materialExchangeOfferRepository.existsByInventoryItemId(survivorId)).thenReturn(false);
    when(inventoryItemRepository.findMergeGroupForUpdate(
            USER_ID, null, gameItem.getId(), LOCATION_ID, null, false, null))
        .thenReturn(List.of(survivor, victim));
    when(inventoryItemRepository.saveAndFlush(survivor)).thenReturn(survivor);

    // When — the client merge flag is false: irrelevant for item rows, which follow the PIECE
    // auto-merge rule
    InventoryItem result = service.mergeStockIfRequested(survivor, false);

    // Then — the group is loaded through the full seven-argument query with the NULL material AND
    // NULL quality branches (the pre-fix plain equalities matched nothing for item rows, silently
    // degenerating their merge to a permanent no-op), and the sibling folds into the survivor.
    assertSame(survivor, result);
    assertEquals(5.0, survivor.getAmount(), 1e-9, "item amounts of all folded rows are summed");
    ArgumentCaptor<UUID> materialKey = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<Integer> qualityKey = ArgumentCaptor.forClass(Integer.class);
    verify(inventoryItemRepository)
        .findMergeGroupForUpdate(
            eq(USER_ID),
            materialKey.capture(),
            eq(gameItem.getId()),
            eq(LOCATION_ID),
            qualityKey.capture(),
            eq(false),
            eq((UUID) null));
    assertNull(materialKey.getValue(), "an item stack keys with materialId = null");
    assertNull(qualityKey.getValue(), "an item stack keys with quality = null");
    verify(inventoryItemRepository).deleteAll(List.of(victim));
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_ITEM_MERGED), eq(survivorId), any(), eq(USER_ID), any());
  }

  @Test
  void nullMaterial_withOptIn_isNoOp() {
    // A row without a material cannot merge (the merge key requires material) and must not NPE on
    // material.getId() even with the SCU per-action opt-in set — pins the null-material early
    // return.
    InventoryItem row = row(UUID.randomUUID(), QuantityType.SCU, 5.0, "n");
    row.setMaterial(null);

    InventoryItem result = service.mergeStockIfRequested(row, true);

    assertSame(row, result);
    assertEquals(5.0, row.getAmount(), 1e-9, "the amount is untouched");
    verifyNoInteractions(inventoryItemRepository, materialExchangeOfferRepository, auditService);
  }
}
