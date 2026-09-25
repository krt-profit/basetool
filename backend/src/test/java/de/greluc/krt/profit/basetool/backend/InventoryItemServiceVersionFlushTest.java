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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that in-place inventory edits ({@code updateNote}, reducing {@code
 * bookOutInventoryItem}) map their DTO from {@code saveAndFlush}, so the returned {@code @Version}
 * is current and the next edit does not 409.
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
  private InventoryItemService inventoryItemService;

  private InventoryCheckoutService realCheckoutService;

  @BeforeEach
  void wireCheckoutDelegate() {
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
    inventoryItemService =
        new InventoryItemService(
            inventoryItemRepository,
            userRepository,
            materialRepository,
            null,
            locationRepository,
            jobOrderRepository,
            missionRepository,
            inventoryItemMapper,
            ownerScopeService,
            null,
            auditService,
            null,
            realCheckoutService);
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

    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            3.0, null, null, CheckoutType.DISCARD, null, null, 0L, null, null, null, null);
    inventoryItemService.bookOutInventoryItem(itemId, dto, userId, false);

    verify(inventoryItemRepository).saveAndFlush(item);
    verify(inventoryItemRepository, never()).save(item);
  }
}
