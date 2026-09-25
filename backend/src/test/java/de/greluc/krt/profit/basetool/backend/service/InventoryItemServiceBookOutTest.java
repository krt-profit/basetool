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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.exception.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Coverage for {@link InventoryItemService#bookOutInventoryItem} — the money- and security-critical
 * "check out" flow that combines optimistic locking, owner-vs-admin authorisation, amount
 * validation, CheckoutType inference (DISCARD / TRANSFER / SELL), partial-vs-full deletion, and the
 * {@code MissionFinanceEntry} side effect for SELL.
 *
 * <p>Coverage analysis flagged this as the largest concentrated branch gap in the service package
 * (19/28 branches uncovered). A bug here means wrong ownership decisions, lost inventory, or
 * double-counted income.
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemServiceBookOutTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private MaterialMapper materialMapper;
  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;
  @InjectMocks private InventoryCheckoutService service;

  private static final UUID ITEM_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final UUID ADMIN_ID = UUID.randomUUID();
  private static final UUID LOCATION_ID = UUID.randomUUID();

  private User owner;
  private Location location;
  private Material material;

  @BeforeEach
  void setUpEntities() {
    owner = new User();
    owner.setId(OWNER_ID);
    owner.setUsername("alice");

    location = new Location();
    location.setId(LOCATION_ID);
    location.setName("ARC-L1");

    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Quantanium");

    lenient()
        .when(inventoryItemMapper.toDto(any(InventoryItem.class)))
        .thenAnswer(inv -> sentinelDto(((InventoryItem) inv.getArgument(0)).getAmount()));
  }

  @Nested
  class GuardTests {

    @Test
    void notFound_throws() {
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, null, null, CheckoutType.DISCARD, null, null, 1L),
                  OWNER_ID,
                  false));
    }

    @Test
    void versionMismatch_throwsOptimisticLockingFailure() {
      InventoryItem item = newItem(10.0, 5L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, null, null, CheckoutType.DISCARD, null, null, 99L),
                  OWNER_ID,
                  false));
    }

    @Test
    void nullVersion_bypassesOptimisticCheck() {
      InventoryItem item = newItem(10.0, 5L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(1.0, null, null, CheckoutType.DISCARD, null, null, null),
          OWNER_ID,
          false);

      verify(inventoryItemRepository).saveAndFlush(item);
    }

    @Test
    void nonOwnerNonAdmin_throwsAccessDenied() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      UUID otherUserId = UUID.randomUUID();
      assertThrows(
          AccessDeniedException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, null, null, CheckoutType.DISCARD, null, null, 1L),
                  otherUserId,
                  false));
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void adminBypassesOwnershipCheck() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      UUID otherUserId = UUID.randomUUID();
      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(1.0, null, null, CheckoutType.DISCARD, null, null, 1L),
          otherUserId,
          true);

      verify(inventoryItemRepository).saveAndFlush(item);
    }

    @Test
    void amountExceedsAvailable_throwsBadRequest() {
      InventoryItem item = newItem(5.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          BadRequestException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(10.0, null, null, CheckoutType.DISCARD, null, null, 1L),
                  OWNER_ID,
                  false));
    }
  }

  @Nested
  class CheckoutTypeInferenceTests {

    @Test
    void nullType_withTargetUser_inferredAsTransfer() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);

      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID, newDto(1.0, targetUserId, null, null, null, null, 1L), OWNER_ID, false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(captor.capture());
      verify(inventoryItemRepository).saveAndFlush(item);
      assertSame(targetUser, captor.getValue().getUser());
    }

    @Test
    void nullType_withTargetLocation_inferredAsTransfer() {
      UUID targetLocationId = UUID.randomUUID();
      Location targetLocation = new Location();
      targetLocation.setId(targetLocationId);

      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(locationRepository.findById(targetLocationId)).thenReturn(Optional.of(targetLocation));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID, newDto(1.0, null, targetLocationId, null, null, null, 1L), OWNER_ID, false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(captor.capture());
      verify(inventoryItemRepository).saveAndFlush(item);
      assertSame(targetLocation, captor.getValue().getLocation());
    }

    @Test
    void nullType_withoutTargets_inferredAsDiscard() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      service.bookOutInventoryItem(
          ITEM_ID, newDto(1.0, null, null, null, null, null, 1L), OWNER_ID, false);

      verify(inventoryItemRepository, org.mockito.Mockito.times(1))
          .saveAndFlush(any(InventoryItem.class));
      verify(materialExchangeOfferRepository).clampOfferedAmountToStock(eq(ITEM_ID), eq(9.0));
    }
  }

  @Nested
  class SellGuardTests {

    @Test
    void sell_withoutTerminal_throwsBadRequest() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          BadRequestException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, null, null, CheckoutType.SELL, null, BigDecimal.TEN, 1L),
                  OWNER_ID,
                  false));
    }

    @Test
    void sell_withBlankTerminal_throwsBadRequest() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          BadRequestException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, null, null, CheckoutType.SELL, "   ", BigDecimal.TEN, 1L),
                  OWNER_ID,
                  false));
    }

    @Test
    void sell_withNullSellAmount_throwsBadRequest() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          BadRequestException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, null, null, CheckoutType.SELL, "TDD-Aphorism", null, 1L),
                  OWNER_ID,
                  false));
    }

    @Test
    void sell_withNegativeSellAmount_throwsBadRequest() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          BadRequestException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, null, null, CheckoutType.SELL, "TDD", BigDecimal.valueOf(-50), 1L),
                  OWNER_ID,
                  false));
    }
  }

  @Nested
  class TransferTests {

    @Test
    void targetUserNotFound_throws() {
      UUID targetUserId = UUID.randomUUID();
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L),
                  OWNER_ID,
                  false));
    }

    @Test
    void targetLocationNotFound_throws() {
      UUID targetLocationId = UUID.randomUUID();
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(locationRepository.findById(targetLocationId)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, null, targetLocationId, CheckoutType.TRANSFER, null, null, 1L),
                  OWNER_ID,
                  false));
    }

    @Test
    void transferToSelfAndSameLocation_throwsBadRequest() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () ->
                  service.bookOutInventoryItem(
                      ITEM_ID,
                      newDto(1.0, OWNER_ID, LOCATION_ID, CheckoutType.TRANSFER, null, null, 1L),
                      OWNER_ID,
                      false));
      assert ex.getMessage().toLowerCase().contains("change");
    }

    @Test
    void explicitTransferWithoutTargets_throwsBadRequestAndDestroysNothing() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () ->
                  service.bookOutInventoryItem(
                      ITEM_ID,
                      newDto(4.0, null, null, CheckoutType.TRANSFER, null, null, 1L),
                      OWNER_ID,
                      false));
      assert ex.getMessage().toLowerCase().contains("target");

      assertEquals(10.0, item.getAmount(), "source amount must be unchanged");
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).saveAndFlush(any());
      verify(inventoryItemRepository, never()).delete(any());
      verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void transferPartial_keepsSourceWithRemainingAmount() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);

      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(3.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L),
          OWNER_ID,
          false);

      ArgumentCaptor<InventoryItem> saveCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      ArgumentCaptor<InventoryItem> flushCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(saveCaptor.capture());
      verify(inventoryItemRepository).saveAndFlush(flushCaptor.capture());
      InventoryItem newItem = saveCaptor.getValue();
      InventoryItem source = flushCaptor.getValue();
      assertEquals(3.0, newItem.getAmount(), "new item gets the booked-out amount");
      assertEquals(7.0, source.getAmount(), "source keeps the remainder");
      assertSame(item, source, "the flushed row is the original source");
      assertSame(targetUser, newItem.getUser());
      verify(inventoryItemRepository, never()).delete(any());
      verify(materialExchangeOfferRepository).clampOfferedAmountToStock(eq(ITEM_ID), eq(7.0));
    }

    @Test
    void transferFull_deletesSourceItem() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);

      InventoryItem item = newItem(5.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(5.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L),
          OWNER_ID,
          false);

      verify(inventoryItemRepository).delete(item);
      verify(inventoryItemRepository, org.mockito.Mockito.times(1)).save(any(InventoryItem.class));
    }

    @Test
    void transferAlwaysInsertsNewRowAtTarget() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);

      InventoryItem source = newItem(10.0, 1L);

      InventoryItem existingTarget = new InventoryItem();
      existingTarget.setId(UUID.randomUUID());
      existingTarget.setUser(targetUser);
      existingTarget.setLocation(location);
      existingTarget.setMaterial(material);
      existingTarget.setQuality(500);
      existingTarget.setPersonal(false);
      existingTarget.setAmount(6.0);
      existingTarget.setVersion(3L);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(source));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(4.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L),
          OWNER_ID,
          false);

      assertEquals(6.0, existingTarget.getAmount(), "existing target stack must be left untouched");
      assertEquals(6.0, source.getAmount(), "source keeps the remainder");
      ArgumentCaptor<InventoryItem> saveCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(saveCaptor.capture());
      InventoryItem newRow = saveCaptor.getValue();
      assertSame(targetUser, newRow.getUser(), "the inserted row is owned by the target user");
      assertEquals(4.0, newRow.getAmount(), "the inserted row carries the moved amount");
      verify(inventoryItemRepository).saveAndFlush(source);
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void transferDoesNotMergeAcrossOwningOrgUnit() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);

      InventoryItem source = newItem(10.0, 1L);

      de.greluc.krt.profit.basetool.backend.model.Squadron orgA =
          new de.greluc.krt.profit.basetool.backend.model.Squadron();
      orgA.setId(UUID.randomUUID());
      InventoryItem foreignOrgTarget = new InventoryItem();
      foreignOrgTarget.setId(UUID.randomUUID());
      foreignOrgTarget.setUser(targetUser);
      foreignOrgTarget.setLocation(location);
      foreignOrgTarget.setMaterial(material);
      foreignOrgTarget.setQuality(500);
      foreignOrgTarget.setPersonal(false);
      foreignOrgTarget.setAmount(6.0);
      foreignOrgTarget.setOwningOrgUnit(orgA);

      de.greluc.krt.profit.basetool.backend.model.Squadron orgB =
          new de.greluc.krt.profit.basetool.backend.model.Squadron();
      orgB.setId(UUID.randomUUID());

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(source));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(any(), any())).thenReturn(orgB);
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(4.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L),
          OWNER_ID,
          false);

      assertEquals(6.0, foreignOrgTarget.getAmount(), "foreign-org stack must be left untouched");
      ArgumentCaptor<InventoryItem> saveCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(saveCaptor.capture());
      verify(inventoryItemRepository).saveAndFlush(source);
      InventoryItem newRow = saveCaptor.getValue();
      assertSame(orgB, newRow.getOwningOrgUnit());
      assertSame(targetUser, newRow.getUser());
      assertEquals(4.0, newRow.getAmount(), "the inserted row carries the moved amount");
    }

    /**
     * Pins change #7: a PARTIAL cross-user transfer persists the reduced source row via {@code
     * saveAndFlush} (not a plain {@code save}) so the row's @Version stays current within the
     * transaction and no future in-place consumer of a transfer can 409, while the brand-new target
     * row is still inserted via a plain {@code save}.
     */
    @Test
    void partialTransfer_flushesReducedSourceRow() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);

      InventoryItem source = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(source));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(3.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L),
          OWNER_ID,
          false);

      verify(inventoryItemRepository).saveAndFlush(source);
      verify(inventoryItemRepository, never()).save(source);
      ArgumentCaptor<InventoryItem> saveCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(saveCaptor.capture());
      assertSame(
          targetUser, saveCaptor.getValue().getUser(), "the saved row is the new target row");
      verify(inventoryItemRepository, never()).delete(any());
    }
  }

  @Nested
  class SellTests {

    @Test
    void sellDeductingFromMission_createsProportionalMissionFinanceEntryIncome() {
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      MissionParticipant participant = new MissionParticipant();
      participant.setId(UUID.randomUUID());

      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addMission(item, mission, 4.0);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(missionParticipantRepository.findByMissionIdAndUserId(mission.getId(), OWNER_ID))
          .thenReturn(Optional.of(participant));

      service.bookOutInventoryItem(
          ITEM_ID,
          newSellDto(
              1.0,
              "TDD",
              BigDecimal.valueOf(500),
              1L,
              List.of(new AllocationReductionDto(mission.getId(), 1.0))),
          OWNER_ID,
          false);

      ArgumentCaptor<MissionFinanceEntry> captor =
          ArgumentCaptor.forClass(MissionFinanceEntry.class);
      verify(missionFinanceEntryRepository).save(captor.capture());
      MissionFinanceEntry entry = captor.getValue();
      assertEquals(FinanceType.INCOME, entry.getType());
      assertEquals(0, entry.getAmount().compareTo(BigDecimal.valueOf(500)));
      assertSame(mission, entry.getMission());
      assertSame(participant, entry.getParticipant());
      assert entry.getNote().contains("Quantanium");
      assert entry.getNote().contains("TDD");
    }

    @Test
    void sellDeductingFromMultipleMissions_booksProportionalIncomePerMission() {
      Mission missionA = new Mission();
      missionA.setId(UUID.randomUUID());
      Mission missionB = new Mission();
      missionB.setId(UUID.randomUUID());
      MissionParticipant participantA = new MissionParticipant();
      participantA.setId(UUID.randomUUID());
      MissionParticipant participantB = new MissionParticipant();
      participantB.setId(UUID.randomUUID());

      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addMission(item, missionA, 1.0);
      InventoryAllocations.addMission(item, missionB, 1.0);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(missionParticipantRepository.findByMissionIdAndUserId(missionA.getId(), OWNER_ID))
          .thenReturn(Optional.of(participantA));
      when(missionParticipantRepository.findByMissionIdAndUserId(missionB.getId(), OWNER_ID))
          .thenReturn(Optional.of(participantB));

      service.bookOutInventoryItem(
          ITEM_ID,
          newSellDto(
              1.0,
              "TDD",
              BigDecimal.valueOf(500),
              1L,
              List.of(
                  new AllocationReductionDto(missionA.getId(), 0.6),
                  new AllocationReductionDto(missionB.getId(), 0.3))),
          OWNER_ID,
          false);

      ArgumentCaptor<MissionFinanceEntry> captor =
          ArgumentCaptor.forClass(MissionFinanceEntry.class);
      verify(missionFinanceEntryRepository, org.mockito.Mockito.times(2)).save(captor.capture());
      assertEquals(0, captor.getAllValues().get(0).getAmount().compareTo(BigDecimal.valueOf(300)));
      assertSame(missionA, captor.getAllValues().get(0).getMission());
      assertEquals(0, captor.getAllValues().get(1).getAmount().compareTo(BigDecimal.valueOf(150)));
      assertSame(missionB, captor.getAllValues().get(1).getMission());
    }

    @Test
    void fullSellFromMission_createsIncomeAndDeletesRow() {
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      MissionParticipant participant = new MissionParticipant();
      participant.setId(UUID.randomUUID());

      InventoryItem item = newItem(5.0, 1L);
      InventoryAllocations.addMission(item, mission, 5.0);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(missionParticipantRepository.findByMissionIdAndUserId(mission.getId(), OWNER_ID))
          .thenReturn(Optional.of(participant));

      InventoryItemDto result =
          service.bookOutInventoryItem(
              ITEM_ID,
              newSellDto(
                  5.0,
                  "TDD",
                  BigDecimal.valueOf(500),
                  1L,
                  List.of(new AllocationReductionDto(mission.getId(), 5.0))),
              OWNER_ID,
              false);

      ArgumentCaptor<MissionFinanceEntry> captor =
          ArgumentCaptor.forClass(MissionFinanceEntry.class);
      verify(missionFinanceEntryRepository).save(captor.capture());
      MissionFinanceEntry entry = captor.getValue();
      assertEquals(FinanceType.INCOME, entry.getType());
      assertEquals(0, entry.getAmount().compareTo(BigDecimal.valueOf(500)));
      assertSame(mission, entry.getMission());
      assertSame(participant, entry.getParticipant());

      verify(inventoryItemRepository).delete(item);
      assertNull(result, "a full sale depletes the stack and returns null");
      verify(inventoryItemRepository, never()).saveAndFlush(any());

      verify(auditService)
          .record(eq(AuditEventType.INVENTORY_ITEM_SOLD), eq(ITEM_ID), any(), eq(OWNER_ID), any());
    }

    @Test
    void sellFromMission_callerNotParticipant_creditsPersonallyNoEntry() {
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addMission(item, mission, 1.0);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(missionParticipantRepository.findByMissionIdAndUserId(mission.getId(), OWNER_ID))
          .thenReturn(Optional.empty());

      service.bookOutInventoryItem(
          ITEM_ID,
          newSellDto(
              1.0,
              "TDD",
              BigDecimal.TEN,
              1L,
              List.of(new AllocationReductionDto(mission.getId(), 1.0))),
          OWNER_ID,
          false);
      verify(missionFinanceEntryRepository, never()).save(any());
    }

    @Test
    void sellDeductingFromMission_notEarmarkedOnTheRow_throwsBadRequest() {
      Mission earmarked = new Mission();
      earmarked.setId(UUID.randomUUID());
      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addMission(item, earmarked, 1.0);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          BadRequestException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newSellDto(
                      1.0,
                      "TDD",
                      BigDecimal.TEN,
                      1L,
                      List.of(new AllocationReductionDto(UUID.randomUUID(), 1.0))),
                  OWNER_ID,
                  false));
      verify(missionFinanceEntryRepository, never()).save(any());
    }

    @Test
    void sellReductionsExceedingSoldAmount_throwsBadRequest() {
      Mission missionA = new Mission();
      missionA.setId(UUID.randomUUID());
      Mission missionB = new Mission();
      missionB.setId(UUID.randomUUID());
      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addMission(item, missionA, 1.0);
      InventoryAllocations.addMission(item, missionB, 1.0);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          BadRequestException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newSellDto(
                      1.0,
                      "TDD",
                      BigDecimal.valueOf(500),
                      1L,
                      List.of(
                          new AllocationReductionDto(missionA.getId(), 0.6),
                          new AllocationReductionDto(missionB.getId(), 0.6))),
                  OWNER_ID,
                  false));
      verify(missionFinanceEntryRepository, never()).save(any());
    }

    @Test
    void sellDuplicateReductionTarget_throwsBadRequest() {
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addMission(item, mission, 2.0);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          BadRequestException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newSellDto(
                      1.0,
                      "TDD",
                      BigDecimal.valueOf(500),
                      1L,
                      List.of(
                          new AllocationReductionDto(mission.getId(), 0.5),
                          new AllocationReductionDto(mission.getId(), 0.5))),
                  OWNER_ID,
                  false));
      verify(missionFinanceEntryRepository, never()).save(any());
    }

    @Test
    void sellWithNoMissionReductions_isFullyPersonalSale_skipsFinanceEntry() {
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addMission(item, mission, 1.0);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      service.bookOutInventoryItem(
          ITEM_ID, newSellDto(1.0, "TDD", BigDecimal.TEN, 1L, List.of()), OWNER_ID, false);

      verify(missionFinanceEntryRepository, never()).save(any());
      verify(missionParticipantRepository, never()).findByMissionIdAndUserId(any(), any());
    }

    @Test
    void sellWithoutMission_skipsFinanceEntry() {
      InventoryItem item = newItem(10.0, 1L);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(1.0, null, null, CheckoutType.SELL, "TDD", BigDecimal.TEN, 1L),
          OWNER_ID,
          false);

      verify(missionFinanceEntryRepository, never()).save(any());
      verify(missionParticipantRepository, never()).findByMissionIdAndUserId(any(), any());
    }
  }

  @Nested
  class DiscardTests {

    @Test
    void discardPartial_updatesSourceAmount() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);

      InventoryItemDto result =
          service.bookOutInventoryItem(
              ITEM_ID,
              newDto(3.0, null, null, CheckoutType.DISCARD, null, null, 1L),
              OWNER_ID,
              false);

      assertEquals(7.0, item.getAmount());
      assertNotNull(result);
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void discardFull_deletesItemAndReturnsNull() {
      InventoryItem item = newItem(5.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      InventoryItemDto result =
          service.bookOutInventoryItem(
              ITEM_ID,
              newDto(5.0, null, null, CheckoutType.DISCARD, null, null, 1L),
              OWNER_ID,
              false);

      assertNull(result, "full discard returns null");
      verify(inventoryItemRepository).delete(item);
      verify(inventoryItemRepository, never()).save(any());
    }

    @Test
    void depletionBoundary_subEpsilonResidualDeletesRow() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      InventoryItemDto result =
          service.bookOutInventoryItem(
              ITEM_ID,
              newDto(9.9999, null, null, CheckoutType.DISCARD, null, null, 1L),
              OWNER_ID,
              false);

      assertNull(result, "a sub-epsilon residual depletes the stack and returns null");
      verify(inventoryItemRepository).delete(item);
      verify(inventoryItemRepository, never()).saveAndFlush(any());
    }

    @Test
    void smallResidualAboveEpsilon_keepsRow() {
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(9.998, null, null, CheckoutType.DISCARD, null, null, 1L),
          OWNER_ID,
          false);

      assertEquals(0.002, item.getAmount(), 1e-9, "the small residual is kept, rounded to 3 dp");
      verify(inventoryItemRepository).saveAndFlush(item);
      verify(inventoryItemRepository, never()).delete(any());
    }
  }

  @Nested
  class AuditTrailTests {

    @Test
    void discardBookOut_recordsConsumedAuditWithPreDeleteSnapshot() {
      InventoryItem item = newItem(5.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      service.bookOutInventoryItem(
          ITEM_ID, newDto(5.0, null, null, CheckoutType.DISCARD, null, null, 1L), OWNER_ID, false);

      verify(inventoryItemRepository).delete(item);
      verify(auditService)
          .record(
              eq(AuditEventType.INVENTORY_ITEM_CONSUMED), eq(ITEM_ID), any(), eq(OWNER_ID), any());
    }

    @Test
    void sellBookOut_recordsSoldAudit() {
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      MissionParticipant participant = new MissionParticipant();
      participant.setId(UUID.randomUUID());

      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addMission(item, mission, 1.0);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(missionParticipantRepository.findByMissionIdAndUserId(mission.getId(), OWNER_ID))
          .thenReturn(Optional.of(participant));
      when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);

      service.bookOutInventoryItem(
          ITEM_ID,
          newSellDto(
              1.0,
              "TDD",
              BigDecimal.valueOf(500),
              1L,
              List.of(new AllocationReductionDto(mission.getId(), 1.0))),
          OWNER_ID,
          false);

      verify(auditService)
          .record(eq(AuditEventType.INVENTORY_ITEM_SOLD), eq(ITEM_ID), any(), eq(OWNER_ID), any());
    }

    @Test
    void transferBookOut_recordsTransferredAuditToTargetUser() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);

      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(3.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L),
          OWNER_ID,
          false);

      verify(auditService)
          .record(
              eq(AuditEventType.INVENTORY_ITEM_TRANSFERRED),
              eq(ITEM_ID),
              any(),
              eq(targetUserId),
              any());
    }
  }

  @Nested
  class ReductionPlanTests {

    @Test
    void discardDeductingFromOrderTag_shrinksThatTagAndAmount() {
      JobOrder order = new JobOrder();
      order.setId(UUID.randomUUID());
      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addJobOrder(item, order, 6.0, false);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);

      InventoryItemBookOutDto dto =
          new InventoryItemBookOutDto(
              3.0,
              null,
              null,
              CheckoutType.DISCARD,
              null,
              null,
              1L,
              null,
              null,
              List.of(new AllocationReductionDto(order.getId(), 3.0)),
              null);

      service.bookOutInventoryItem(ITEM_ID, dto, OWNER_ID, false);

      assertEquals(7.0, item.getAmount());
      assertEquals(3.0, InventoryAllocations.jobOrderSlice(item, order.getId()).getAmount(), 1e-9);
    }

    @Test
    void discardUnderAssignedPlan_throwsOverAllocation() {
      JobOrder order = new JobOrder();
      order.setId(UUID.randomUUID());
      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addJobOrder(item, order, 8.0, false);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      InventoryItemBookOutDto dto =
          new InventoryItemBookOutDto(
              5.0, null, null, CheckoutType.DISCARD, null, null, 1L, null, null, List.of(), null);

      assertThrows(
          OverAllocationException.class,
          () -> service.bookOutInventoryItem(ITEM_ID, dto, OWNER_ID, false));
    }

    @Test
    void transferCarriesReducedTagsToDestination() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);
      JobOrder order = new JobOrder();
      order.setId(UUID.randomUUID());
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());

      InventoryItem item = newItem(10.0, 1L);
      InventoryAllocations.addJobOrder(item, order, 6.0, true);
      InventoryAllocations.addMission(item, mission, 4.0);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      InventoryItemBookOutDto dto =
          new InventoryItemBookOutDto(
              3.0,
              targetUserId,
              null,
              CheckoutType.TRANSFER,
              null,
              null,
              1L,
              null,
              null,
              List.of(new AllocationReductionDto(order.getId(), 3.0)),
              List.of(new AllocationReductionDto(mission.getId(), 2.0)));

      service.bookOutInventoryItem(ITEM_ID, dto, OWNER_ID, false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
      InventoryItem moved =
          captor.getAllValues().stream()
              .filter(i -> i.getUser() == targetUser)
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected the moved row to be saved"));

      assertEquals(3.0, InventoryAllocations.jobOrderSlice(moved, order.getId()).getAmount(), 1e-9);
      org.junit.jupiter.api.Assertions.assertTrue(
          InventoryAllocations.jobOrderSlice(moved, order.getId()).getDelivered());
      assertEquals(
          2.0, InventoryAllocations.missionSlice(moved, mission.getId()).getAmount(), 1e-9);
      assertEquals(3.0, InventoryAllocations.jobOrderSlice(item, order.getId()).getAmount(), 1e-9);
      assertEquals(2.0, InventoryAllocations.missionSlice(item, mission.getId()).getAmount(), 1e-9);
      assertEquals(7.0, item.getAmount());
    }
  }

  @Nested
  class GameItemRowTests {

    @Test
    void bookOut_itemRow_fractionalAmount_throwsBadRequest() {
      InventoryItem item = newGameItemRow(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () ->
                  service.bookOutInventoryItem(
                      ITEM_ID,
                      newDto(1.5, null, null, CheckoutType.DISCARD, null, null, 1L),
                      OWNER_ID,
                      false));
      assertEquals("Amount must be a whole number for item stock", ex.getMessage());
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).saveAndFlush(any());
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void bookOut_pieceMaterial_fractionalAmount_throwsBadRequest() {
      InventoryItem item = newItem(10.0, 1L);
      item.getMaterial().setQuantityType(QuantityType.PIECE);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () ->
                  service.bookOutInventoryItem(
                      ITEM_ID,
                      newDto(2.5, null, null, CheckoutType.DISCARD, null, null, 1L),
                      OWNER_ID,
                      false));
      assertEquals("Amount must be a whole number for PIECE materials", ex.getMessage());
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).saveAndFlush(any());
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void bookOut_pieceMaterial_legacyFractionalRow_fullDepletion_isAllowed() {
      InventoryItem item = newItem(1.5, 1L);
      item.getMaterial().setQuantityType(QuantityType.PIECE);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      InventoryItemDto result =
          service.bookOutInventoryItem(
              ITEM_ID,
              newDto(1.5, null, null, CheckoutType.DISCARD, null, null, 1L),
              OWNER_ID,
              false);

      assertNull(result, "a full depletion returns null");
      verify(inventoryItemRepository).delete(item);
      verify(inventoryItemRepository, never()).saveAndFlush(any());
    }

    @Test
    void bookOut_pieceMaterial_legacyFractionalRow_partialFractionalAmount_throwsBadRequest() {
      InventoryItem item = newItem(1.5, 1L);
      item.getMaterial().setQuantityType(QuantityType.PIECE);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () ->
                  service.bookOutInventoryItem(
                      ITEM_ID,
                      newDto(0.5, null, null, CheckoutType.DISCARD, null, null, 1L),
                      OWNER_ID,
                      false));
      assertEquals("Amount must be a whole number for PIECE materials", ex.getMessage());
      verify(inventoryItemRepository, never()).saveAndFlush(any());
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void bookOut_itemRow_legacyFractionalRow_fullDepletion_isAllowed() {
      InventoryItem item = newGameItemRow(1.5, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      InventoryItemDto result =
          service.bookOutInventoryItem(
              ITEM_ID,
              newDto(1.5, null, null, CheckoutType.DISCARD, null, null, 1L),
              OWNER_ID,
              false);

      assertNull(result, "a full depletion returns null");
      verify(inventoryItemRepository).delete(item);
      verify(inventoryItemRepository, never()).saveAndFlush(any());
    }

    @Test
    void bookOut_itemRow_legacyFractionalRow_partialFractionalAmount_throwsBadRequest() {
      InventoryItem item = newGameItemRow(1.5, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () ->
                  service.bookOutInventoryItem(
                      ITEM_ID,
                      newDto(0.5, null, null, CheckoutType.DISCARD, null, null, 1L),
                      OWNER_ID,
                      false));
      assertEquals("Amount must be a whole number for item stock", ex.getMessage());
      verify(inventoryItemRepository, never()).saveAndFlush(any());
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void bookOut_itemRow_missionReductions_throwsBadRequest() {
      InventoryItem item = newGameItemRow(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      InventoryItemBookOutDto dto =
          new InventoryItemBookOutDto(
              2.0,
              null,
              null,
              CheckoutType.DISCARD,
              null,
              null,
              1L,
              null,
              null,
              null,
              List.of(new AllocationReductionDto(UUID.randomUUID(), 1.0)));

      assertThrows(
          BadRequestException.class,
          () -> service.bookOutInventoryItem(ITEM_ID, dto, OWNER_ID, false));
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void transfer_itemRow_copiesGameItemOntoMovedRow() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);
      InventoryItem item = newGameItemRow(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(4.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L),
          OWNER_ID,
          false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
      InventoryItem moved =
          captor.getAllValues().stream()
              .filter(i -> i.getUser() == targetUser)
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected the moved item row to be saved"));
      assertSame(item.getGameItem(), moved.getGameItem());
      assertNull(moved.getMaterial());
      assertNull(moved.getQuality());
      assertEquals(4.0, moved.getAmount());
    }

    @Test
    void transfer_itemRow_fullAmount_carriesJobOrderEarmarkOntoMovedRow() {
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);
      JobOrder order = new JobOrder();
      order.setId(UUID.randomUUID());
      InventoryItem item = newGameItemRow(4.0, 1L);
      InventoryAllocations.addJobOrder(item, order, 4.0, true);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(userRepository.findById(targetUserId)).thenReturn(Optional.of(targetUser));
      when(inventoryItemRepository.save(any(InventoryItem.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(4.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L),
          OWNER_ID,
          false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
      InventoryItem moved =
          captor.getAllValues().stream()
              .filter(i -> i.getUser() == targetUser)
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected the moved item row to be saved"));
      assertSame(item.getGameItem(), moved.getGameItem());
      var slice = InventoryAllocations.jobOrderSlice(moved, order.getId());
      org.junit.jupiter.api.Assertions.assertNotNull(
          slice, "the order earmark must ride onto the moved item row");
      assertEquals(4.0, slice.getAmount(), 1e-9);
      org.junit.jupiter.api.Assertions.assertTrue(
          slice.getDelivered(), "the delivered marker must be inherited");
    }
  }

  /**
   * Builds a game-item stock row sharing the fixture identity (owner, location, non-personal):
   * gameItem set, material and quality {@code null} — the V220 catalog shape (REQ-INV-029).
   *
   * @param amount the row's amount
   * @param version the row's optimistic-lock version
   * @return the assembled item row
   */
  private InventoryItem newGameItemRow(double amount, Long version) {
    InventoryItem item = newItem(amount, version);
    item.setMaterial(null);
    item.setQuality(null);
    GameItem gameItem = new GameItem();
    gameItem.setId(UUID.randomUUID());
    gameItem.setName("Quantum Drive");
    item.setGameItem(gameItem);
    return item;
  }

  private InventoryItem newItem(double amount, Long version) {
    InventoryItem item = new InventoryItem();
    item.setId(ITEM_ID);
    item.setAmount(amount);
    item.setQuality(500);
    item.setUser(owner);
    item.setLocation(location);
    item.setMaterial(material);
    item.setPersonal(false);
    item.setVersion(version);
    return item;
  }

  private static InventoryItemBookOutDto newDto(
      double amount,
      UUID targetUserId,
      UUID targetLocationId,
      CheckoutType type,
      String terminal,
      BigDecimal sellAmount,
      Long version) {
    return new InventoryItemBookOutDto(
        amount,
        targetUserId,
        targetLocationId,
        type,
        terminal,
        sellAmount,
        version,
        null,
        null,
        null,
        null);
  }

  /**
   * Builds a {@code SELL} book-out DTO carrying the mission "deduct from" plan (Variante C). The
   * coupled proceeds are derived from it: each mission is credited a share of {@code sellAmount}
   * proportional to the SCU sourced from its earmark.
   *
   * @param amount the sold quantity
   * @param terminal the sale terminal
   * @param sellAmount the total sale proceeds
   * @param version the optimistic-lock version
   * @param missionReductions the SCU sourced per mission earmark
   * @return the SELL DTO
   */
  private static InventoryItemBookOutDto newSellDto(
      double amount,
      String terminal,
      BigDecimal sellAmount,
      Long version,
      List<AllocationReductionDto> missionReductions) {
    return new InventoryItemBookOutDto(
        amount,
        null,
        null,
        CheckoutType.SELL,
        terminal,
        sellAmount,
        version,
        null,
        null,
        null,
        missionReductions);
  }

  @Test
  void bookOutInventoryItem_transferWithTargetOwningOrgUnitId_routesThroughResolver() {
    UUID itemId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID pickedOrgUnitId = UUID.randomUUID();

    de.greluc.krt.profit.basetool.backend.model.User owner =
        new de.greluc.krt.profit.basetool.backend.model.User();
    owner.setId(UUID.randomUUID());
    de.greluc.krt.profit.basetool.backend.model.User targetUser =
        new de.greluc.krt.profit.basetool.backend.model.User();
    targetUser.setId(targetUserId);
    de.greluc.krt.profit.basetool.backend.model.Squadron picked =
        new de.greluc.krt.profit.basetool.backend.model.Squadron();
    picked.setId(pickedOrgUnitId);

    de.greluc.krt.profit.basetool.backend.model.InventoryItem item =
        new de.greluc.krt.profit.basetool.backend.model.InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setAmount(10.0);
    item.setUser(owner);
    de.greluc.krt.profit.basetool.backend.model.Location loc =
        new de.greluc.krt.profit.basetool.backend.model.Location();
    loc.setId(UUID.randomUUID());
    item.setLocation(loc);
    item.setMaterial(new de.greluc.krt.profit.basetool.backend.model.Material());
    item.setPersonal(false);

    org.mockito.Mockito.when(inventoryItemRepository.findById(itemId))
        .thenReturn(java.util.Optional.of(item));
    org.mockito.Mockito.when(userRepository.findById(targetUserId))
        .thenReturn(java.util.Optional.of(targetUser));
    org.mockito.Mockito.when(
            ownerScopeService.resolveOrgUnitForPickerOutputNullable(targetUser, pickedOrgUnitId))
        .thenReturn(picked);
    org.mockito.ArgumentCaptor<de.greluc.krt.profit.basetool.backend.model.InventoryItem> captor =
        org.mockito.ArgumentCaptor.forClass(
            de.greluc.krt.profit.basetool.backend.model.InventoryItem.class);
    org.mockito.Mockito.when(
            inventoryItemRepository.save(
                any(de.greluc.krt.profit.basetool.backend.model.InventoryItem.class)))
        .thenAnswer(i -> i.getArguments()[0]);

    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            5.0,
            targetUserId,
            null,
            CheckoutType.TRANSFER,
            null,
            null,
            1L,
            pickedOrgUnitId,
            null,
            null,
            null);

    service.bookOutInventoryItem(itemId, dto, owner.getId(), false);

    org.mockito.Mockito.verify(inventoryItemRepository, org.mockito.Mockito.atLeastOnce())
        .save(captor.capture());
    java.util.List<de.greluc.krt.profit.basetool.backend.model.InventoryItem> saved =
        captor.getAllValues();
    de.greluc.krt.profit.basetool.backend.model.InventoryItem newRow =
        saved.stream()
            .filter(i -> i.getUser() == targetUser)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a save for the new transfer row"));
    org.junit.jupiter.api.Assertions.assertSame(
        picked,
        newRow.getOwningOrgUnit(),
        "picker output must flow through resolveOrgUnitForPickerOutputNullable on the new row");
  }

  private static InventoryItemDto sentinelDto(Double amount) {
    return new InventoryItemDto(
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        500,
        amount,
        false,
        java.util.List.of(),
        0.0,
        java.util.List.of(),
        0.0,
        null,
        null,
        1L,
        null,
        null);
  }
}
