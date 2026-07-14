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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.InventoryCheckoutService;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression tests for the optimistic-lock {@code @Version} write-back on the in-place inventory
 * edits (epic #571 / #577). The in-place edits ({@code updateNote} and the reducing {@code
 * bookOutInventoryItem}) return the item DTO whose version the frontend writes back onto every
 * control in the same row. Because the service is {@code @Transactional} (the commit — and thus the
 * {@code @Version} increment — happens after the method returns), the DTO must be mapped from a
 * {@code saveAndFlush}, not a plain {@code save}: a plain {@code save} leaves the version
 * unflushed, so the response carries the STALE version and the user's next in-place edit of the
 * same row fails with {@code ObjectOptimisticLockingFailureException} (HTTP 409). These tests pin
 * {@code saveAndFlush}, mirroring the {@code verify(...).flush()} guard in {@code
 * InventoryItemServiceBulkCheckoutTest}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemServiceVersionFlushTest {

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

  @Mock private AuditService auditService;
  @Mock private OwnerScopeService ownerScopeService;
  @InjectMocks private InventoryItemService inventoryItemService;

  private InventoryCheckoutService realCheckoutService;

  @BeforeEach
  void wireCheckoutDelegate() {
    // The facade delegates the book-out flow to InventoryCheckoutService; Mockito does not inject
    // one @InjectMocks target into another, so build the real sub-service from the same mocks and
    // set it on the facade. updateNote stays on the facade and uses the mocks directly.
    realCheckoutService =
        new InventoryCheckoutService(
            inventoryItemRepository,
            userRepository,
            locationRepository,
            missionFinanceEntryRepository,
            missionParticipantRepository,
            materialExchangeOfferRepository,
            inventoryItemMapper,
            ownerScopeService,
            auditService);
    ReflectionTestUtils.setField(
        inventoryItemService, "inventoryCheckoutService", realCheckoutService);
  }

  private User userWithId(UUID id) {
    User u = new User();
    u.setId(id);
    return u;
  }

  private InventoryItem item(UUID itemId, UUID ownerId, double amount) {
    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setUser(userWithId(ownerId));
    item.setPersonal(false);
    item.setVersion(0L);
    item.setAmount(amount);
    return item;
  }

  @Test
  void updateNote_flushesSoTheReturnedVersionIsCurrent() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItem item = item(itemId, userId, 15.0);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));

    inventoryItemService.updateNote(
        itemId, new InventoryItemNoteUpdateRequest("a note", 0L), userId, false);

    verify(inventoryItemRepository).saveAndFlush(item);
    verify(inventoryItemRepository, never()).save(item);
  }

  @Test
  void partialBookOut_flushesSoTheReducedRowReturnsTheCurrentVersion() {
    UUID userId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    InventoryItem item = item(itemId, userId, 10.0);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));

    // DISCARD a partial amount so the row is reduced (not deleted) and its DTO is returned.
    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            3.0, null, null, CheckoutType.DISCARD, null, null, 0L, null, null, null);
    inventoryItemService.bookOutInventoryItem(itemId, dto, userId, false);

    verify(inventoryItemRepository).saveAndFlush(item);
    verify(inventoryItemRepository, never()).save(item);
  }
}
