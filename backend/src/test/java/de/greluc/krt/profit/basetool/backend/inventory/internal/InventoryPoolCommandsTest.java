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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The pool and donation commands of the Lager (REQ-INV-057, REQ-INV-058): book-in and transfer into
 * an org unit's pool, the donation earmark and the protection it gives a row.
 */
@ExtendWith(MockitoExtension.class)
class InventoryPoolCommandsTest {

  private static final UUID DONOR = UUID.randomUUID();
  private static final UUID HOLDER = UUID.randomUUID();

  @Mock private InventoryItemRepository inventoryRepository;
  @Mock private InventoryDonationAllocationRepository donationAllocationRepository;
  @Mock private UserRepository userRepository;
  @Mock private InventoryCheckoutService checkoutService;
  @Mock private InventoryStolenMarkService stolenMarkService;
  @Mock private AuditRecorder auditRecorder;
  @Mock private StockChangeObserver stockChangeObserver;

  @InjectMocks private InventoryStockCommands commands;

  private Material material;
  private Location location;
  private OrgUnit pool;
  private User donor;
  private User holder;

  @BeforeEach
  void setUp() {
    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Quantainium");
    material.setQuantityType(QuantityType.SCU);
    location = new Location();
    location.setId(UUID.randomUUID());
    location.setName("Lorville");
    pool = new Squadron();
    pool.setId(UUID.randomUUID());
    donor = new User();
    donor.setId(DONOR);
    holder = new User();
    holder.setId(HOLDER);
  }

  private InventoryItem donorRow(double amount) {
    InventoryItem row = new InventoryItem();
    row.setId(UUID.randomUUID());
    row.setUser(donor);
    row.setMaterial(material);
    row.setLocation(location);
    row.setQuality(500);
    row.setAmount(amount);
    row.setPersonal(true);
    row.setStolen(false);
    return row;
  }

  private void stubSaveAssignsAnId() {
    when(inventoryRepository.save(any(InventoryItem.class)))
        .thenAnswer(invocation -> {
          InventoryItem item = invocation.getArgument(0);
          if (item.getId() == null) {
            item.setId(UUID.randomUUID());
          }
          return item;
        });
  }

  @Test
  void aPoolBookInCreatesASharedRowStampedWithTheUnitAndMergesIt() {
    when(userRepository.findById(HOLDER)).thenReturn(Optional.of(holder));
    stubSaveAssignsAnId();
    when(checkoutService.mergeStockIfRequested(any(InventoryItem.class), eq(true)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    UUID rowId =
        commands.bookIntoPool(
            HOLDER,
            pool,
            new StockLot(material, null, location, 500, false),
            3.5,
            StockBookingReason.DONATION);

    ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryRepository).save(saved.capture());
    assertThat(saved.getValue().getPersonal()).isFalse();
    assertThat(saved.getValue().getOwningOrgUnit()).isSameAs(pool);
    assertThat(saved.getValue().getUser()).isSameAs(holder);
    assertThat(saved.getValue().getAmount()).isEqualTo(3.5);
    assertThat(rowId).isEqualTo(saved.getValue().getId());
    verify(auditRecorder)
        .record(eq(AuditEventType.INVENTORY_ITEM_CREATED), any(), any(), eq(HOLDER), any());
  }

  @Test
  void aPoolBookInRefusesAFractionOfAPieceCountedMaterial() {
    material.setQuantityType(QuantityType.PIECE);
    when(userRepository.findById(HOLDER)).thenReturn(Optional.of(holder));

    assertThatThrownBy(() ->
            commands.bookIntoPool(
                HOLDER,
                pool,
                new StockLot(material, null, location, 0, false),
                1.5,
                StockBookingReason.PURCHASE))
        .isInstanceOf(BadRequestException.class);
    verify(inventoryRepository, never()).save(any());
  }

  @Test
  void aPoolBookInRefusesANonPositiveAmount() {
    assertThatThrownBy(() ->
            commands.bookIntoPool(
                HOLDER,
                pool,
                new StockLot(material, null, location, 500, false),
                0.0,
                StockBookingReason.DONATION))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aPartialTransferLowersTheSourceAndBooksTheAmountIntoThePool() {
    InventoryItem source = donorRow(10.0);
    when(inventoryRepository.findById(source.getId())).thenReturn(Optional.of(source));
    when(userRepository.findById(HOLDER)).thenReturn(Optional.of(holder));
    stubSaveAssignsAnId();
    when(inventoryRepository.saveAndFlush(any(InventoryItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(checkoutService.mergeStockIfRequested(any(InventoryItem.class), eq(true)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Location target = new Location();
    target.setId(UUID.randomUUID());
    target.setName("Port Tressler");

    commands.transferToPool(source.getId(), 4.0, pool, HOLDER, target, StockBookingReason.DONATION);

    assertThat(source.getAmount()).isEqualTo(6.0);
    ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryRepository).save(saved.capture());
    assertThat(saved.getValue().getAmount()).isEqualTo(4.0);
    assertThat(saved.getValue().getLocation()).isSameAs(target);
    assertThat(saved.getValue().getQuality()).isEqualTo(500);
    assertThat(saved.getValue().getPersonal()).isFalse();
    verify(stockChangeObserver).lower(source.getId(), 6.0, StockChangeReason.TRANSFER);
    verify(auditRecorder)
        .record(eq(AuditEventType.INVENTORY_ITEM_TRANSFERRED), any(), any(), eq(HOLDER), any());
  }

  @Test
  void aFullTransferDeletesTheSourceAndTellsTheOffers() {
    InventoryItem source = donorRow(4.0);
    when(inventoryRepository.findById(source.getId())).thenReturn(Optional.of(source));
    when(userRepository.findById(HOLDER)).thenReturn(Optional.of(holder));
    stubSaveAssignsAnId();
    when(checkoutService.mergeStockIfRequested(any(InventoryItem.class), eq(true)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    commands.transferToPool(
        source.getId(), 4.0, pool, HOLDER, location, StockBookingReason.DONATION);

    verify(stockChangeObserver).beforeDelete(List.of(source.getId()), StockChangeReason.TRANSFER);
    verify(inventoryRepository).delete(source);
  }

  @Test
  void aTransferRefusesMoreThanTheRowHolds() {
    InventoryItem source = donorRow(2.0);
    when(inventoryRepository.findById(source.getId())).thenReturn(Optional.of(source));

    assertThatThrownBy(() ->
            commands.transferToPool(
                source.getId(), 3.0, pool, HOLDER, location, StockBookingReason.DONATION))
        .isInstanceOf(BadRequestException.class);
    verify(inventoryRepository, never()).save(any());
  }

  @Test
  void aTransferThatLeavesAnotherPledgeUncoveredIsRefused() {
    InventoryItem source = donorRow(10.0);
    InventoryAllocations.addDonation(source, UUID.randomUUID(), 8.0);
    when(inventoryRepository.findById(source.getId())).thenReturn(Optional.of(source));
    when(userRepository.findById(HOLDER)).thenReturn(Optional.of(holder));

    assertThatThrownBy(() ->
            commands.transferToPool(
                source.getId(), 4.0, pool, HOLDER, location, StockBookingReason.DONATION))
        .isInstanceOf(OverAllocationException.class);
    verify(inventoryRepository, never()).saveAndFlush(any());
  }

  @Test
  void aPoolCommandPublishesNoEventOfItsOwn() {
    for (Field field : InventoryStockCommands.class.getDeclaredFields()) {
      assertThat(field.getType()).isNotEqualTo(ApplicationEventPublisher.class);
    }
  }

  @Test
  void anEarmarkAddsASliceAndRecordsIt() {
    InventoryItem row = donorRow(10.0);
    UUID donationId = UUID.randomUUID();
    when(inventoryRepository.findById(row.getId())).thenReturn(Optional.of(row));

    commands.earmarkForDonation(row.getId(), donationId, 6.0);

    assertThat(row.donationReserved()).isEqualTo(6.0);
    verify(inventoryRepository).saveAndFlush(row);
    verify(auditRecorder)
        .record(eq(AuditEventType.INVENTORY_ALLOCATION_ADDED), any(), any(), eq(DONOR), any());
  }

  @Test
  void anEarmarkBeyondTheRowIsRefused() {
    InventoryItem row = donorRow(5.0);
    when(inventoryRepository.findById(row.getId())).thenReturn(Optional.of(row));

    assertThatThrownBy(() -> commands.earmarkForDonation(row.getId(), UUID.randomUUID(), 6.0))
        .isInstanceOf(OverAllocationException.class);
    verify(inventoryRepository, never()).saveAndFlush(any());
  }

  @Test
  void stolenStockCannotBeEarmarked() {
    InventoryItem row = donorRow(5.0);
    row.setStolen(true);
    when(inventoryRepository.findById(row.getId())).thenReturn(Optional.of(row));

    assertThatThrownBy(() -> commands.earmarkForDonation(row.getId(), UUID.randomUUID(), 1.0))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aReleaseRemovesTheSliceFromEveryRow() {
    InventoryItem row = donorRow(10.0);
    UUID donationId = UUID.randomUUID();
    InventoryDonationAllocation slice = InventoryAllocations.addDonation(row, donationId, 6.0);
    when(donationAllocationRepository.findByDonationId(donationId)).thenReturn(List.of(slice));

    commands.releaseDonationEarmark(donationId);

    assertThat(row.getDonationAllocations()).isEmpty();
    verify(auditRecorder)
        .record(eq(AuditEventType.INVENTORY_ALLOCATION_REMOVED), any(), any(), eq(DONOR), any());
  }

  @Test
  void aPledgedAmountCannotBeBookedOutOfTheRow() {
    InventoryItem row = donorRow(10.0);
    InventoryAllocations.addDonation(row, UUID.randomUUID(), 8.0);
    row.setAmount(5.0);

    assertThat(InventoryAllocations.fits(row)).isFalse();
    assertThatThrownBy(() -> InventoryDonationGuards.requireDonationFits(row))
        .isInstanceOf(OverAllocationException.class);
  }

  @Test
  void aPledgedRowCannotBeDepletedOrMovedInFull() {
    InventoryItem row = donorRow(10.0);
    InventoryAllocations.addDonation(row, UUID.randomUUID(), 2.0);

    assertThatThrownBy(() -> InventoryDonationGuards.requireNoDonation(row))
        .isInstanceOf(OverAllocationException.class);
  }

  @Test
  void aMergeKeepsTheDonationSlicesOfTheFoldedRow() {
    InventoryItem survivor = donorRow(5.0);
    InventoryItem victim = donorRow(5.0);
    UUID donationId = UUID.randomUUID();
    InventoryAllocations.addDonation(victim, donationId, 3.0);
    survivor.setAmount(10.0);

    InventoryAllocations.unionInto(survivor, victim);

    assertThat(survivor.donationReserved()).isEqualTo(3.0);
    verify(inventoryRepository, never()).delete(any());
  }

  @Test
  void theLotKeyMatchesTheKeyTheExchangeLocksUnder() {
    assertThat(new StockLot(material, null, location, null, false).key())
        .isEqualTo("m:" + material.getId() + "|l:" + location.getId() + "|q:0|s:0");
  }

  @Test
  void anAmountOfZeroNeverReachesTheRepository() {
    assertThatThrownBy(() -> commands.earmarkForDonation(UUID.randomUUID(), UUID.randomUUID(), 0))
        .isInstanceOf(BadRequestException.class);
    verify(inventoryRepository, never()).findById(any());
    verify(stockChangeObserver, never()).lower(any(), anyDouble(), any());
  }
}
