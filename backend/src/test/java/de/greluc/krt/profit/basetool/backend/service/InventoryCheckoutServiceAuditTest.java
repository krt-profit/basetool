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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * REQ-AUDIT-001 audit-trail coverage for the two non-book-out state mutations of {@link
 * InventoryCheckoutService} — the delivered-flag toggle ({@link
 * InventoryCheckoutService#updateDelivered}) and the admin global-wipe ({@link
 * InventoryCheckoutService#deleteAllGlobalInventory}).
 *
 * <p>These paths were exercised for their flag/return/scope behaviour elsewhere but their audited
 * side effects (INVENTORY_ITEM_DELIVERY_TOGGLED, INVENTORY_WIPED) were never verified. Dropping or
 * mis-typing either audit call would leave the audited Lager area with no trace of a delivered
 * toggle or — most damaging — of the destructive global wipe, with nothing failing. This test
 * drives the service directly against Mockito mocks and pins the exact audit events.
 */
@ExtendWith(MockitoExtension.class)
class InventoryCheckoutServiceAuditTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuditService auditService;

  @InjectMocks private InventoryCheckoutService service;

  @Test
  void updateDelivered_recordsDeliveryToggledAudit() {
    // Gap 4: toggling the delivered flag records INVENTORY_ITEM_DELIVERY_TOGGLED against the row id
    // and its owner.
    UUID itemId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    User owner = new User();
    owner.setId(ownerId);

    InventoryItem item = new InventoryItem();
    item.setId(itemId);
    item.setVersion(1L);
    item.setUser(owner);
    item.setDelivered(false);

    UpdateDeliveredRequest request = new UpdateDeliveredRequest(true, 1L);

    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));
    when(inventoryItemRepository.saveAndFlush(item)).thenReturn(item);
    when(inventoryItemMapper.toDto(item)).thenReturn(null);

    service.updateDelivered(itemId, request, ownerId, false);

    assertTrue(item.getDelivered(), "the delivered flag is flipped to true");
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_ITEM_DELIVERY_TOGGLED),
            eq(itemId),
            any(),
            eq(ownerId),
            any());
  }

  @Test
  void deleteAllGlobalInventory_recordsWipedAuditWithScopeAndCount() {
    // Gap 4: the admin global wipe records INVENTORY_WIPED (aggregate-less: null
    // subject/label/target
    // user) with the scope and the removed count in its details payload.
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(inventoryItemRepository.deleteAllNonPersonal(true, null, Set.of())).thenReturn(42);

    int removed = service.deleteAllGlobalInventory();

    assertEquals(42, removed);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_WIPED), isNull(), isNull(), isNull(), details.capture());
    String rendered = details.getValue().toString();
    assertTrue(rendered.contains("removed=42"), "audit details carry the removed count");
    assertTrue(rendered.contains("scope=adminAll"), "audit details carry the wipe scope");
  }
}
