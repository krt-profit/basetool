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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerOrgUnitRequiredException;
import de.greluc.krt.profit.basetool.backend.support.InventoryProperties;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Covers {@link InventoryItemService#hasMergeCandidates}, the Einbuchen merge probe (REQ-INV-026).
 */
@ExtendWith(MockitoExtension.class)
class InventoryMergeProbeTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID MATERIAL = UUID.randomUUID();
  private static final UUID LOCATION = UUID.randomUUID();

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private OwnerScopeService ownerScopeService;

  private InventoryItemService service;
  private User caller;

  @BeforeEach
  void setUp() {
    service = serviceWithStolenMarking(false);
    caller = new User();
    caller.setId(CALLER);
  }

  private InventoryItemService serviceWithStolenMarking(boolean enabled) {
    return new InventoryItemService(
        inventoryItemRepository,
        userRepository,
        null,
        null,
        null,
        null,
        null,
        null,
        ownerScopeService,
        null,
        null,
        null,
        null,
        new InventoryProperties(enabled));
  }

  @Test
  void answersTrueWhenARowWithTheResolvedIdentityExists() {
    UUID unitId = UUID.randomUUID();
    Squadron unit = new Squadron();
    unit.setId(unitId);
    when(userRepository.findById(CALLER)).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, null)).thenReturn(unit);
    when(inventoryItemRepository.countMergeCandidates(
            CALLER, MATERIAL, LOCATION, 500, false, false, unitId))
        .thenReturn(2L);

    assertTrue(
        service.hasMergeCandidates(null, CALLER, MATERIAL, LOCATION, 500, false, false, null));
  }

  @Test
  void answersFalseWhenNoRowMatches() {
    when(userRepository.findById(CALLER)).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, null)).thenReturn(null);
    when(inventoryItemRepository.countMergeCandidates(
            CALLER, MATERIAL, LOCATION, 500, true, false, null))
        .thenReturn(0L);

    assertFalse(
        service.hasMergeCandidates(null, CALLER, MATERIAL, LOCATION, 500, true, false, null));
  }

  @Test
  void answersFalseWhenTheOwningUnitCannotBeResolved() {
    when(userRepository.findById(CALLER)).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, null))
        .thenThrow(new OwnerOrgUnitRequiredException("ambiguous"));

    assertFalse(
        service.hasMergeCandidates(null, CALLER, MATERIAL, LOCATION, 500, false, false, null));
    verify(inventoryItemRepository, never())
        .countMergeCandidates(any(), any(), any(), anyInt(), anyBoolean(), anyBoolean(), any());
  }

  @Test
  void answersFalseWhenThePickedUnitIsRefused() {
    UUID foreignUnit = UUID.randomUUID();
    when(userRepository.findById(CALLER)).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, foreignUnit))
        .thenThrow(new BadRequestException("not a membership"));

    assertFalse(
        service.hasMergeCandidates(
            null, CALLER, MATERIAL, LOCATION, 500, false, false, foreignUnit));
  }

  @Test
  void refusesToProbeAnotherMembersStockWithoutBookingScope() {
    UUID other = UUID.randomUUID();
    when(ownerScopeService.canManageUserInventory(other)).thenReturn(false);

    assertThrows(
        AccessDeniedException.class,
        () ->
            service.hasMergeCandidates(other, CALLER, MATERIAL, LOCATION, 500, false, false, null));
    verifyNoInteractions(inventoryItemRepository, userRepository);
  }

  @Test
  void probesAnotherMembersSharedStockWithBookingScope() {
    UUID other = UUID.randomUUID();
    User target = new User();
    target.setId(other);
    when(ownerScopeService.canManageUserInventory(other)).thenReturn(true);
    when(userRepository.findById(other)).thenReturn(Optional.of(target));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(target, null)).thenReturn(null);
    when(inventoryItemRepository.countMergeCandidates(
            other, MATERIAL, LOCATION, 500, false, false, null))
        .thenReturn(1L);

    assertTrue(
        service.hasMergeCandidates(other, CALLER, MATERIAL, LOCATION, 500, false, false, null));
  }

  @Test
  void answersFalseForPersonalStockOfAnotherMember() {
    UUID other = UUID.randomUUID();
    when(ownerScopeService.canManageUserInventory(other)).thenReturn(true);

    assertFalse(
        service.hasMergeCandidates(other, CALLER, MATERIAL, LOCATION, 500, true, false, null));
    verifyNoInteractions(inventoryItemRepository);
  }

  @Test
  void answersFalseForStolenStockWhileMarkingIsOff() {
    assertFalse(
        service.hasMergeCandidates(null, CALLER, MATERIAL, LOCATION, 500, false, true, null));
    verifyNoInteractions(inventoryItemRepository);
  }

  @Test
  void probesStolenStockWhileMarkingIsOn() {
    InventoryItemService marking = serviceWithStolenMarking(true);
    when(userRepository.findById(CALLER)).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, null)).thenReturn(null);
    when(inventoryItemRepository.countMergeCandidates(
            CALLER, MATERIAL, LOCATION, 500, false, true, null))
        .thenReturn(1L);

    assertTrue(
        marking.hasMergeCandidates(null, CALLER, MATERIAL, LOCATION, 500, false, true, null));
  }
}
