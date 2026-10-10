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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link StockViewerAccessService}: a Lager row's {@code canEdit} answers both gates
 * of the per-row writes, the scope gate and then owner or Logistician-or-above.
 */
@ExtendWith(MockitoExtension.class)
class StockViewerAccessServiceTest {

  private static final UUID ITEM_ID = UUID.randomUUID();
  private static final UUID CALLER_ID = UUID.randomUUID();
  private static final UUID OTHER_MEMBER_ID = UUID.randomUUID();

  @Mock private InventoryAccessPolicy inventoryAccessPolicy;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private StockViewerAccessService service;

  @Test
  void mayEditInventoryItem_ownRowInScope_isTrueForAPlainMember() {
    when(inventoryAccessPolicy.canEditInventoryItem(ITEM_ID)).thenReturn(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER_ID));

    assertTrue(service.mayEditInventoryItem(ITEM_ID, CALLER_ID));
  }

  @Test
  void mayEditInventoryItem_anotherMembersRowInScope_isFalseForAPlainMember() {
    when(inventoryAccessPolicy.canEditInventoryItem(ITEM_ID)).thenReturn(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER_ID));
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    assertFalse(service.mayEditInventoryItem(ITEM_ID, OTHER_MEMBER_ID));
  }

  @Test
  void mayEditInventoryItem_anotherMembersRowInScope_isTrueForALogisticianOrAbove() {
    when(inventoryAccessPolicy.canEditInventoryItem(ITEM_ID)).thenReturn(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER_ID));
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

    assertTrue(service.mayEditInventoryItem(ITEM_ID, OTHER_MEMBER_ID));
  }

  @Test
  void mayEditInventoryItem_rowOutOfScope_isFalseEvenForALogisticianOrAbove() {
    when(inventoryAccessPolicy.canEditInventoryItem(ITEM_ID)).thenReturn(false);
    lenient().when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

    assertFalse(service.mayEditInventoryItem(ITEM_ID, OTHER_MEMBER_ID));
  }

  @Test
  void mayEditInventoryItem_unknownOwner_countsAsNotTheCaller() {
    when(inventoryAccessPolicy.canEditInventoryItem(ITEM_ID)).thenReturn(true);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    assertFalse(service.mayEditInventoryItem(ITEM_ID, null));
    verify(authHelperService, never()).currentUserId();
  }

  @Test
  void mayEditInventoryItem_unauthenticatedCaller_isNotTheOwner() {
    when(inventoryAccessPolicy.canEditInventoryItem(ITEM_ID)).thenReturn(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    assertFalse(service.mayEditInventoryItem(ITEM_ID, CALLER_ID));
  }

  @Test
  void mayEditInventoryItem_nullItemId_isFalseWithoutAskingTheScopeGate() {
    assertFalse(service.mayEditInventoryItem(null, CALLER_ID));
    verify(inventoryAccessPolicy, never()).canEditInventoryItem(any());
  }
}
