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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkStolenMarkRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkStolenMarkResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pins the rules of the „gestohlen" marker (REQ-INV-053): refused while switched off, no event
 * without a change, a whole row flips in place, a part splits off as a new row that keeps the rest
 * of the identity, a split never undercuts a Materialbörse offer or the earmarks, and a selection
 * takes only the caller's own rows.
 */
@ExtendWith(MockitoExtension.class)
class InventoryStolenMarkServiceTest {

  private static final UUID CALLER = UUID.randomUUID();

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private InventoryCheckoutService inventoryCheckoutService;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private AuditService auditService;

  private InventoryStolenMarkService service;

  @BeforeEach
  void setUp() {
    service = serviceWith(true);
  }

  /**
   * Builds the service with the marker switched on or off.
   *
   * @param enabled the switch
   * @return the service under test
   */
  private InventoryStolenMarkService serviceWith(boolean enabled) {
    return new InventoryStolenMarkService(
        inventoryItemRepository,
        materialExchangeOfferRepository,
        inventoryCheckoutService,
        inventoryItemMapper,
        new InventoryProperties(enabled),
        auditService);
  }

  /**
   * Builds a row of the caller with the given amount and quantity type.
   *
   * @param amount the row amount
   * @param quantityType SCU or PIECE
   * @return the row
   */
  private static InventoryItem row(double amount, QuantityType quantityType) {
    User owner = new User();
    owner.setId(CALLER);
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Quantanium");
    material.setQuantityType(quantityType);
    InventoryItem item = new InventoryItem();
    item.setId(UUID.randomUUID());
    item.setUser(owner);
    item.setMaterial(material);
    item.setQuality(700);
    item.setAmount(amount);
    item.setPersonal(true);
    item.setStolen(false);
    item.setVersion(3L);
    return item;
  }

  /**
   * Makes the repository and the merge hand back what they were given.
   *
   * @param item the locked row
   */
  private void stubRow(InventoryItem item) {
    when(inventoryItemRepository.findByIdForRebook(item.getId())).thenReturn(Optional.of(item));
  }

  /** Makes save and merge return their argument. */
  private void stubWrites() {
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(
            inv -> {
              InventoryItem saved = inv.getArgument(0);
              saved.setId(UUID.randomUUID());
              return saved;
            });
    when(inventoryCheckoutService.mergeStockIfRequested(any(InventoryItem.class), eq(false)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void everyChangeIsRefusedWhileTheMarkerIsSwitchedOff() {
    InventoryStolenMarkService off = serviceWith(false);
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> off.mark(id, new InventoryItemStolenMarkDto(1L, true, null), CALLER))
        .isInstanceOf(BusinessConflictException.class)
        .hasMessage("error.inventory.stolen.disabled");
    assertThatThrownBy(() -> off.bulkMark(new BulkStolenMarkRequest(List.of(id), true), CALLER))
        .isInstanceOf(BusinessConflictException.class);
    verifyNoInteractions(inventoryItemRepository, auditService);
  }

  @Test
  void aRowAlreadyCarryingTheMarkerIsLeftAloneAndNothingIsRecorded() {
    InventoryItem item = row(10.0, QuantityType.SCU);
    item.setStolen(true);
    stubRow(item);

    service.mark(item.getId(), new InventoryItemStolenMarkDto(3L, true, null), CALLER);

    verify(inventoryItemRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditService);
  }

  @Test
  void aWholeRowFlipsInPlaceAndMergesIntoItsNewStack() {
    InventoryItem item = row(10.0, QuantityType.SCU);
    stubRow(item);
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryCheckoutService.mergeStockIfRequested(item, false)).thenReturn(item);

    service.mark(item.getId(), new InventoryItemStolenMarkDto(3L, true, 10.0), CALLER);

    assertThat(item.getStolen()).isTrue();
    assertThat(item.getAmount()).isEqualTo(10.0);
    verify(inventoryItemRepository, never()).save(any());
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_STOLEN_MARKED),
            eq(item.getId()),
            any(),
            eq(CALLER),
            argThat(d -> d.toString().contains("split")));
    verify(inventoryCheckoutService).mergeStockIfRequested(item, false);
  }

  @Test
  void aPartSplitsOffAsANewRowThatKeepsTheRestOfTheIdentity() {
    InventoryItem item = row(10.0, QuantityType.SCU);
    stubRow(item);
    stubWrites();
    when(materialExchangeOfferRepository.findByInventoryItemIdAndStatus(
            item.getId(), MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.empty());

    service.mark(item.getId(), new InventoryItemStolenMarkDto(3L, true, 4.0), CALLER);

    ArgumentCaptor<InventoryItem> part = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(part.capture());
    assertThat(part.getValue().getStolen()).isTrue();
    assertThat(part.getValue().getAmount()).isEqualTo(4.0);
    assertThat(part.getValue().getMaterial()).isSameAs(item.getMaterial());
    assertThat(part.getValue().getQuality()).isEqualTo(700);
    assertThat(part.getValue().getPersonal()).isTrue();
    assertThat(item.getStolen()).isFalse();
    assertThat(item.getAmount()).isEqualTo(6.0);
    verify(inventoryItemRepository).saveAndFlush(item);
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_STOLEN_MARKED), eq(item.getId()), any(), eq(CALLER), any());
  }

  @Test
  void removingTheMarkerFromAPartRecordsTheUnmarkEvent() {
    InventoryItem item = row(10.0, QuantityType.SCU);
    item.setStolen(true);
    stubRow(item);
    stubWrites();
    when(materialExchangeOfferRepository.findByInventoryItemIdAndStatus(
            item.getId(), MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.empty());

    service.mark(item.getId(), new InventoryItemStolenMarkDto(3L, false, 2.5), CALLER);

    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_STOLEN_UNMARKED),
            eq(item.getId()),
            any(),
            eq(CALLER),
            any());
    assertThat(item.getAmount()).isEqualTo(7.5);
  }

  @Test
  void aSplitMayNotLeaveTheRowBelowItsOffer() {
    InventoryItem item = row(10.0, QuantityType.SCU);
    stubRow(item);
    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setOfferedAmount(8.0);
    when(materialExchangeOfferRepository.findByInventoryItemIdAndStatus(
            item.getId(), MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.of(offer));

    assertThatThrownBy(
            () -> service.mark(item.getId(), new InventoryItemStolenMarkDto(3L, true, 4.0), CALLER))
        .isInstanceOf(BusinessConflictException.class)
        .hasMessage("error.inventory.stolen.belowOffer");
    verify(inventoryItemRepository, never()).save(any());
    verifyNoInteractions(auditService);
  }

  @Test
  void aSplitThatKeepsTheOfferedAmountIsAllowed() {
    InventoryItem item = row(10.0, QuantityType.SCU);
    stubRow(item);
    stubWrites();
    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setOfferedAmount(6.0);
    when(materialExchangeOfferRepository.findByInventoryItemIdAndStatus(
            item.getId(), MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.of(offer));

    service.mark(item.getId(), new InventoryItemStolenMarkDto(3L, true, 4.0), CALLER);

    assertThat(item.getAmount()).isEqualTo(6.0);
  }

  @Test
  void aSplitMayNotLeaveTheEarmarksWithoutStock() {
    InventoryItem item = row(10.0, QuantityType.SCU);
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    InventoryAllocations.addJobOrder(item, order, 8.0, false);
    stubRow(item);
    when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(materialExchangeOfferRepository.findByInventoryItemIdAndStatus(
            item.getId(), MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.mark(item.getId(), new InventoryItemStolenMarkDto(3L, true, 4.0), CALLER))
        .isInstanceOf(OverAllocationException.class);
  }

  @Test
  void aFractionalPartOfPieceGoodsIsRefused() {
    InventoryItem item = row(10.0, QuantityType.PIECE);
    stubRow(item);

    assertThatThrownBy(
            () -> service.mark(item.getId(), new InventoryItemStolenMarkDto(3L, true, 1.5), CALLER))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.inventory.stolen.wholeUnits");
  }

  @Test
  void aPartLargerThanTheRowIsRefused() {
    InventoryItem item = row(10.0, QuantityType.SCU);
    stubRow(item);

    assertThatThrownBy(
            () ->
                service.mark(item.getId(), new InventoryItemStolenMarkDto(3L, true, 11.0), CALLER))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("error.inventory.stolen.amountTooLarge");
  }

  @Test
  void aSelectionChangesOnlyWhatDiffersAndRecordsOneSummary() {
    InventoryItem plain = row(5.0, QuantityType.SCU);
    InventoryItem already = row(3.0, QuantityType.SCU);
    already.setStolen(true);
    stubRow(plain);
    stubRow(already);
    when(inventoryItemRepository.saveAndFlush(plain)).thenReturn(plain);
    when(inventoryCheckoutService.mergeStockIfRequested(plain, false)).thenReturn(plain);

    BulkStolenMarkResultDto result =
        service.bulkMark(
            new BulkStolenMarkRequest(List.of(plain.getId(), already.getId()), true), CALLER);

    assertThat(result).isEqualTo(new BulkStolenMarkResultDto(1, 1));
    assertThat(plain.getStolen()).isTrue();
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_BULK_STOLEN_CHANGED),
            isNull(),
            isNull(),
            eq(CALLER),
            any());
  }

  @Test
  void aSelectionWithAForeignRowIsRefusedBeforeAnyWrite() {
    InventoryItem own = row(5.0, QuantityType.SCU);
    InventoryItem foreign = row(5.0, QuantityType.SCU);
    User other = new User();
    other.setId(UUID.randomUUID());
    foreign.setUser(other);
    when(inventoryItemRepository.findByIdForRebook(any()))
        .thenAnswer(inv -> Optional.of(inv.getArgument(0).equals(own.getId()) ? own : foreign));

    assertThatThrownBy(
            () ->
                service.bulkMark(
                    new BulkStolenMarkRequest(List.of(own.getId(), foreign.getId()), true), CALLER))
        .isInstanceOf(AccessDeniedException.class);
    verify(inventoryItemRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditService);
  }
}
