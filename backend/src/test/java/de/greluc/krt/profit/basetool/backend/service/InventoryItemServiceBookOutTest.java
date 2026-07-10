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
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
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

    // The mapper is called on the saved item; return a sentinel DTO so we can
    // verify the return value identity.
    lenient()
        .when(inventoryItemMapper.toDto(any(InventoryItem.class)))
        .thenAnswer(inv -> sentinelDto(((InventoryItem) inv.getArgument(0)).getAmount()));
  }

  // ---------------------------------------------------------------
  // Up-front guards
  // ---------------------------------------------------------------

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
      // dto.version() == null skips the explicit check; Hibernate's
      // UPDATE-WHERE-VERSION fallback still catches stale writes in prod.
      InventoryItem item = newItem(10.0, 5L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(1.0, null, null, CheckoutType.DISCARD, null, null, null),
          OWNER_ID,
          false);

      // No exception -> the version check was bypassed.
      verify(inventoryItemRepository).saveAndFlush(item);
    }

    @Test
    void nonOwnerNonAdmin_throwsAccessDenied() {
      // SECURITY: a normal user trying to book out someone else's item -> 403.
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
          /* isAdmin= */ true);

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

  // ---------------------------------------------------------------
  // CheckoutType inference (when type is null)
  // ---------------------------------------------------------------

  @Nested
  class CheckoutTypeInferenceTests {

    @Test
    void nullType_withTargetUser_inferredAsTransfer() {
      // dto.type == null + targetUserId != null -> infer TRANSFER.
      // Verified indirectly: TRANSFER path saves a new InventoryItem;
      // DISCARD path doesn't.
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
          newDto(1.0, targetUserId, null, /* type= */ null, null, null, 1L),
          OWNER_ID,
          false);

      // Partial TRANSFER -> the new target row is save()d, the reduced source row is
      // saveAndFlush()ed (so its @Version stays current within the transaction; see change #7).
      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(captor.capture());
      verify(inventoryItemRepository).saveAndFlush(item);
      // The captured save = the new item with targetUser.
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
          ITEM_ID,
          newDto(1.0, null, targetLocationId, /* type= */ null, null, null, 1L),
          OWNER_ID,
          false);

      // Partial TRANSFER -> new target row save()d, reduced source row saveAndFlush()ed.
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
          ITEM_ID, newDto(1.0, null, null, /* type= */ null, null, null, 1L), OWNER_ID, false);

      // DISCARD -> source updated, no transfer-side new item created.
      verify(inventoryItemRepository, org.mockito.Mockito.times(1))
          .saveAndFlush(any(InventoryItem.class));
    }
  }

  // ---------------------------------------------------------------
  // SELL validation guards
  // ---------------------------------------------------------------

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
                  newDto(
                      1.0, null, null, CheckoutType.SELL, /* terminal= */ null, BigDecimal.TEN, 1L),
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

  // ---------------------------------------------------------------
  // TRANSFER subtree
  // ---------------------------------------------------------------

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
      // BOTH targets resolve to the existing item's user + location -> no-op
      // transfer must be rejected.
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
      // REQ-INV-025: an explicit type=TRANSFER carrying neither a target user nor a target location
      // has nowhere to move the stock to. It must be rejected with 400 up front — it must NOT fall
      // through to the consume tail, which would silently decrement/delete the source and mislog it
      // as INVENTORY_ITEM_CONSUMED with type=TRANSFER (the destructive fall-through this pins
      // shut).
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

      // Nothing is written: the source keeps its full amount, no row is inserted or deleted.
      assertEquals(10.0, item.getAmount(), "source amount must be unchanged");
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).saveAndFlush(any());
      verify(inventoryItemRepository, never()).delete(any());
      // And no audit event — a rejected request records nothing (no spurious CONSUMED event).
      verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void transferPartial_keepsSourceWithRemainingAmount() {
      // amount=3 out of 10 -> source keeps 7, new item gets 3.
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

      // Partial TRANSFER -> new target row save()d, reduced source row saveAndFlush()ed (change
      // #7).
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
    }

    @Test
    void transferFull_deletesSourceItem() {
      // amount == available -> remaining <= QUANTITY_EPSILON -> source deleted.
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
      // Only the new item save call (the source is deleted, not saved).
      verify(inventoryItemRepository, org.mockito.Mockito.times(1)).save(any(InventoryItem.class));
    }

    @Test
    void transferAlwaysInsertsNewRowAtTarget() {
      // Append-only Lager: a transfer always inserts its own brand-new row at the target carrying
      // the moved amount — it is never folded into an existing identical stack there. The source is
      // decremented by the moved amount; the group-on-read view collapses same-identity rows for
      // display, so no visible duplicate results.
      UUID targetUserId = UUID.randomUUID();
      User targetUser = new User();
      targetUser.setId(targetUserId);

      // Source: alice @ ARC-L1, Quantanium, quality 500, 10 units.
      InventoryItem source = newItem(10.0, 1L);

      // An identical stack already exists at the target (same location/material/quality, 6 units) —
      // it must be left untouched.
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

      // The existing target stack is never merged into and keeps its 6 units. Two saves happen: the
      // new target row (amount 4, owned by targetUser) and the decremented source (10 - 4 = 6).
      assertEquals(6.0, existingTarget.getAmount(), "existing target stack must be left untouched");
      assertEquals(6.0, source.getAmount(), "source keeps the remainder");
      // Partial TRANSFER -> new target row save()d, decremented source row saveAndFlush()ed.
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
      // Append-only Lager: the transfer never folds into a pre-existing stack, regardless of its
      // owning org unit. An identical-looking target stack in a different org unit is left
      // untouched and the moved stock is inserted as a brand-new row stamped with the resolved org.
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

      // foreign-org target untouched; a brand-new row stamped org B is created instead.
      assertEquals(6.0, foreignOrgTarget.getAmount(), "foreign-org stack must be left untouched");
      // Partial TRANSFER -> new target row save()d, decremented source row saveAndFlush()ed.
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

      // The reduced source row is flushed; the new target row is the only plain save().
      verify(inventoryItemRepository).saveAndFlush(source);
      verify(inventoryItemRepository, never()).save(source);
      ArgumentCaptor<InventoryItem> saveCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(saveCaptor.capture());
      assertSame(
          targetUser, saveCaptor.getValue().getUser(), "the saved row is the new target row");
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void explicitTransferWithoutTargets_fallsThroughToDiscard() {
      // Gap 5: an explicit type=TRANSFER with NEITHER a target user NOR a target location fails the
      // guard `checkoutType == TRANSFER && (targetUserId != null || targetLocationId != null)`, so
      // the transfer branch is skipped and control falls through to the DISCARD/consume tail. This
      // pins the CURRENT contract: the request is NOT rejected — the source stock is simply
      // decremented as a discard and NO new target row is inserted (never save()). (See the
      // book-out spec note on this unguarded target-less TRANSFER stock loss.)
      InventoryItem item = newItem(10.0, 1L);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);

      service.bookOutInventoryItem(
          ITEM_ID, newDto(4.0, null, null, CheckoutType.TRANSFER, null, null, 1L), OWNER_ID, false);

      // Discard fall-through: source decremented in place, NO new target row inserted.
      assertEquals(6.0, item.getAmount(), "source is decremented as a discard, not transferred");
      verify(inventoryItemRepository).saveAndFlush(item);
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).delete(any());
      // The tail records a CONSUMED (discard) event, not a TRANSFERRED event.
      verify(auditService)
          .record(
              eq(AuditEventType.INVENTORY_ITEM_CONSUMED), eq(ITEM_ID), any(), eq(OWNER_ID), any());
      verify(auditService, never())
          .record(eq(AuditEventType.INVENTORY_ITEM_TRANSFERRED), any(), any(), any(), any());
    }
  }

  // ---------------------------------------------------------------
  // SELL subtree — MissionFinanceEntry side effect
  // ---------------------------------------------------------------

  @Nested
  class SellTests {

    @Test
    void sellWithMission_createsMissionFinanceEntryIncome() {
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      MissionParticipant participant = new MissionParticipant();
      participant.setId(UUID.randomUUID());

      InventoryItem item = newItem(10.0, 1L);
      item.setMission(mission);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(missionParticipantRepository.findByMissionIdAndUserId(mission.getId(), OWNER_ID))
          .thenReturn(Optional.of(participant));

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(1.0, null, null, CheckoutType.SELL, "TDD", BigDecimal.valueOf(500), 1L),
          OWNER_ID,
          false);

      ArgumentCaptor<MissionFinanceEntry> captor =
          ArgumentCaptor.forClass(MissionFinanceEntry.class);
      verify(missionFinanceEntryRepository).save(captor.capture());
      MissionFinanceEntry entry = captor.getValue();
      assertEquals(FinanceType.INCOME, entry.getType());
      assertEquals(BigDecimal.valueOf(500), entry.getAmount());
      assertSame(mission, entry.getMission());
      assertSame(participant, entry.getParticipant());
      assert entry.getNote().contains("Quantanium");
      assert entry.getNote().contains("TDD");
    }

    @Test
    void fullSellWithMission_createsIncomeAndDeletesRow() {
      // Gap 1: a SELL of the WHOLE mission-linked stack (amount == available). The squadron INCOME
      // MissionFinanceEntry must be created off the still-managed row BEFORE the depletion branch
      // deletes it, the source row is delete()d (not saveAndFlush()ed), the method returns null so
      // the frontend drops the depleted row, and the SOLD audit still carries the pre-delete id.
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      MissionParticipant participant = new MissionParticipant();
      participant.setId(UUID.randomUUID());

      InventoryItem item = newItem(5.0, 1L);
      item.setMission(mission);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(missionParticipantRepository.findByMissionIdAndUserId(mission.getId(), OWNER_ID))
          .thenReturn(Optional.of(participant));

      InventoryItemDto result =
          service.bookOutInventoryItem(
              ITEM_ID,
              newDto(5.0, null, null, CheckoutType.SELL, "TDD", BigDecimal.valueOf(500), 1L),
              OWNER_ID,
              false);

      // INCOME finance entry created from the managed (not-yet-deleted) row.
      ArgumentCaptor<MissionFinanceEntry> captor =
          ArgumentCaptor.forClass(MissionFinanceEntry.class);
      verify(missionFinanceEntryRepository).save(captor.capture());
      MissionFinanceEntry entry = captor.getValue();
      assertEquals(FinanceType.INCOME, entry.getType());
      assertEquals(BigDecimal.valueOf(500), entry.getAmount());
      assertSame(mission, entry.getMission());
      assertSame(participant, entry.getParticipant());

      // Depleted -> source row deleted, null returned (frontend removes the row), never re-saved.
      verify(inventoryItemRepository).delete(item);
      assertNull(result, "a full sale depletes the stack and returns null");
      verify(inventoryItemRepository, never()).saveAndFlush(any());

      // The SOLD audit carries the pre-delete source id snapshot and the owner as target user.
      verify(auditService)
          .record(eq(AuditEventType.INVENTORY_ITEM_SOLD), eq(ITEM_ID), any(), eq(OWNER_ID), any());
    }

    @Test
    void sellWithMission_butCallerNotParticipant_throwsBadRequest() {
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      InventoryItem item = newItem(10.0, 1L);
      item.setMission(mission);

      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(missionParticipantRepository.findByMissionIdAndUserId(mission.getId(), OWNER_ID))
          .thenReturn(Optional.empty());

      assertThrows(
          BadRequestException.class,
          () ->
              service.bookOutInventoryItem(
                  ITEM_ID,
                  newDto(1.0, null, null, CheckoutType.SELL, "TDD", BigDecimal.TEN, 1L),
                  OWNER_ID,
                  false));
      verify(missionFinanceEntryRepository, never()).save(any());
    }

    @Test
    void sellWithoutMission_skipsFinanceEntry() {
      // No mission attached -> the SELL just decrements the item, no
      // MissionFinanceEntry side-effect.
      InventoryItem item = newItem(10.0, 1L);
      item.setMission(null);

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

  // ---------------------------------------------------------------
  // DISCARD / partial-vs-full deletion (default branch)
  // ---------------------------------------------------------------

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
      // Gap 3: booking out 9.9999 of 10.0 leaves 0.0001, which roundAmount() rounds to 0.000 — at
      // or below QUANTITY_EPSILON (1e-4), so the row is DELETED (no phantom near-zero sliver is
      // stranded in the append-only Lager) and null is returned.
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
      // Gap 3: booking out 9.998 of 10.0 leaves 0.002 (> QUANTITY_EPSILON 1e-4), so the row is KEPT
      // with the rounded 0.002 residual and saveAndFlush()ed — never deleted. Pins that the epsilon
      // guard does not swallow a legitimate small remainder.
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

  // ---------------------------------------------------------------
  // REQ-AUDIT-001 Lager audit trail (Gap 2)
  // ---------------------------------------------------------------

  @Nested
  class AuditTrailTests {

    @Test
    void discardBookOut_recordsConsumedAuditWithPreDeleteSnapshot() {
      // Gap 2: a DISCARD that depletes the row still records INVENTORY_ITEM_CONSUMED carrying the
      // pre-delete source id snapshot — the audit trail survives the row deletion.
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
      // Gap 2: a SELL records INVENTORY_ITEM_SOLD against the source id and the owner.
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      MissionParticipant participant = new MissionParticipant();
      participant.setId(UUID.randomUUID());

      InventoryItem item = newItem(10.0, 1L);
      item.setMission(mission);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      when(missionParticipantRepository.findByMissionIdAndUserId(mission.getId(), OWNER_ID))
          .thenReturn(Optional.of(participant));
      when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);

      service.bookOutInventoryItem(
          ITEM_ID,
          newDto(1.0, null, null, CheckoutType.SELL, "TDD", BigDecimal.valueOf(500), 1L),
          OWNER_ID,
          false);

      verify(auditService)
          .record(eq(AuditEventType.INVENTORY_ITEM_SOLD), eq(ITEM_ID), any(), eq(OWNER_ID), any());
    }

    @Test
    void transferBookOut_recordsTransferredAuditToTargetUser() {
      // Gap 2: a TRANSFER records INVENTORY_ITEM_TRANSFERRED against the source id, with the TARGET
      // user (not the owner) as the audit's target user.
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

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

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
        amount, targetUserId, targetLocationId, type, terminal, sellAmount, version, null);
  }

  // --- R5.d.g TRANSFER picker delegation -----------------------------------

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
            5.0, targetUserId, null, CheckoutType.TRANSFER, null, null, 1L, pickedOrgUnitId);

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
        500,
        amount,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        1L,
        null);
  }
}
