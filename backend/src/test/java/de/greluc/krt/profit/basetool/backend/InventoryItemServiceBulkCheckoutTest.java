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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.InventoryCheckoutService;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/** Unit tests for the bulk checkout functionality in {@link InventoryCheckoutService}. */
@ExtendWith(MockitoExtension.class)
class InventoryItemServiceBulkCheckoutTest {

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

  @Mock private AuditService auditService;
  @InjectMocks private InventoryCheckoutService inventoryItemService;

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private User userWithId(UUID id) {
    User u = new User();
    u.setId(id);
    return u;
  }

  private InventoryItem itemOwnedBy(UUID itemId, UUID ownerId) {
    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setUser(userWithId(ownerId));
    return item;
  }

  // -------------------------------------------------------------------------
  // Tests
  // -------------------------------------------------------------------------

  @Test
  void bulkCheckout_successfullyRemovesMultipleItems() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID itemId1 = UUID.randomUUID();
    UUID itemId2 = UUID.randomUUID();

    InventoryItem item1 = itemOwnedBy(itemId1, userId);
    InventoryItem item2 = itemOwnedBy(itemId2, userId);

    when(inventoryItemRepository.findByIdForUpdate(itemId1)).thenReturn(Optional.of(item1));
    when(inventoryItemRepository.findByIdForUpdate(itemId2)).thenReturn(Optional.of(item2));

    BulkCheckoutRequest request = new BulkCheckoutRequest(List.of(itemId1, itemId2));

    // When
    inventoryItemService.bulkCheckout(request, userId);

    // Then – deleted in one batch (the association-clearing loop and its flush were removed;
    // each row's job-order / mission allocations cascade away with it, FK ON DELETE CASCADE, V217).
    verify(inventoryItemRepository).deleteAllById(List.of(itemId1, itemId2));
  }

  @Test
  void bulkCheckout_recordsBulkCheckedOutAuditEventWithCount() {
    // Gap 4: the bulk checkout of the audited Lager area must record INVENTORY_BULK_CHECKED_OUT,
    // scoped to the acting user, with the removed count in its details payload.
    UUID userId = UUID.randomUUID();
    UUID itemId1 = UUID.randomUUID();
    UUID itemId2 = UUID.randomUUID();

    when(inventoryItemRepository.findByIdForUpdate(itemId1))
        .thenReturn(Optional.of(itemOwnedBy(itemId1, userId)));
    when(inventoryItemRepository.findByIdForUpdate(itemId2))
        .thenReturn(Optional.of(itemOwnedBy(itemId2, userId)));

    BulkCheckoutRequest request = new BulkCheckoutRequest(List.of(itemId1, itemId2));

    inventoryItemService.bulkCheckout(request, userId);

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_BULK_CHECKED_OUT),
            isNull(),
            isNull(),
            eq(userId),
            details.capture());
    assertTrue(
        details.getValue().toString().contains("count=2"),
        "the audit details must carry the removed item count");
  }

  @Test
  void bulkCheckout_deletesEarmarkedItem_allocationsCascadeAway() {
    // Given – an item earmarked to a job order and a mission. Variante C (REQ-INV-027): the
    // earmarks now live in the entry's allocation collections, not on scalar columns, and a bulk
    // checkout no longer clears them in code — the batch deleteAllById cascades the job-order /
    // mission slices away with the row (FK ON DELETE CASCADE, V217).
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();

    InventoryItem item = itemOwnedBy(itemId, userId);
    item.setAmount(10.0);
    JobOrder jobOrder = new JobOrder();
    jobOrder.setId(UUID.randomUUID());
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    InventoryAllocations.addJobOrder(item, jobOrder, item.getAmount(), false);
    InventoryAllocations.addMission(item, mission, item.getAmount());

    when(inventoryItemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));

    BulkCheckoutRequest request = new BulkCheckoutRequest(List.of(itemId));

    // When
    inventoryItemService.bulkCheckout(request, userId);

    // Then – the earmarked row is removed in the batch delete; its allocations cascade with it.
    verify(inventoryItemRepository).deleteAllById(List.of(itemId));
  }

  @Test
  void bulkCheckout_throwsAccessDenied_whenItemBelongsToAnotherUser() {
    // Given
    UUID currentUserId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();

    InventoryItem item = itemOwnedBy(itemId, otherUserId);
    when(inventoryItemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));

    BulkCheckoutRequest request = new BulkCheckoutRequest(List.of(itemId));

    // When / Then
    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.bulkCheckout(request, currentUserId));

    verify(inventoryItemRepository, never()).deleteAllById(any());
  }

  @Test
  void bulkCheckout_throwsNotFound_whenItemDoesNotExist() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID missingItemId = UUID.randomUUID();

    when(inventoryItemRepository.findByIdForUpdate(missingItemId)).thenReturn(Optional.empty());

    BulkCheckoutRequest request = new BulkCheckoutRequest(List.of(missingItemId));

    // When / Then
    NotFoundException ex =
        assertThrows(
            NotFoundException.class, () -> inventoryItemService.bulkCheckout(request, userId));

    verify(inventoryItemRepository, never()).deleteAllById(any());
  }

  @Test
  void bulkCheckoutRequest_failsValidation_whenItemIdsIsEmpty() {
    // Given
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    BulkCheckoutRequest request = new BulkCheckoutRequest(List.of());

    // When
    Set<ConstraintViolation<BulkCheckoutRequest>> violations = validator.validate(request);

    // Then
    assertFalse(violations.isEmpty(), "Validation should fail for empty itemIds list");
  }

  @Test
  void bulkCheckoutRequest_failsValidation_whenItemIdsIsNull() {
    // Given
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    BulkCheckoutRequest request = new BulkCheckoutRequest(null);

    // When
    Set<ConstraintViolation<BulkCheckoutRequest>> violations = validator.validate(request);

    // Then
    assertFalse(violations.isEmpty(), "Validation should fail for null itemIds");
  }

  @Test
  void bulkCheckout_stopsImmediately_whenFirstItemBelongsToOtherUser() {
    // Given – two items, first belongs to another user
    UUID currentUserId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    UUID itemId1 = UUID.randomUUID();
    UUID itemId2 = UUID.randomUUID();

    InventoryItem foreignItem = itemOwnedBy(itemId1, otherUserId);
    when(inventoryItemRepository.findByIdForUpdate(itemId1)).thenReturn(Optional.of(foreignItem));

    BulkCheckoutRequest request = new BulkCheckoutRequest(List.of(itemId1, itemId2));

    // When / Then
    assertThrows(
        AccessDeniedException.class,
        () -> inventoryItemService.bulkCheckout(request, currentUserId));

    // Second item must never be fetched
    verify(inventoryItemRepository, never()).findByIdForUpdate(itemId2);
    verify(inventoryItemRepository, never()).deleteAllById(any());
  }
}
