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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReconciliationBusyException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies that reference changes refresh the owning surface only after transaction commit. */
@ExtendWith(MockitoExtension.class)
class InventoryItemReassignmentTest {
  @Mock private InventoryItemReassignmentRepository repository;
  @Mock private AuditRecorder audit;
  @Mock private LiveSyncPublisher publisher;
  @Mock private InventoryItemRepository inventoryRepository;

  @Test
  void changedReferencesAnnounceOnlyTheirOwningRoomsAfterCommit() {
    UUID retired = UUID.randomUUID();
    UUID canonical = UUID.randomUUID();
    when(repository.stock(retired, canonical)).thenReturn(List.of(UUID.randomUUID()));
    var participant =
        new InventoryItemReassignment(repository, inventoryRepository, audit, publisher);

    assertThat(participant.reassignGameItem(retired, canonical)).isEqualTo(1);

    verify(publisher)
        .publishAfterCommit(
            LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL),
            List.of("stock", "stock-levels"));
  }

  @Test
  void unchangedReferencesAnnounceNothing() {
    var participant =
        new InventoryItemReassignment(repository, inventoryRepository, audit, publisher);

    assertThat(participant.reassignGameItem(UUID.randomUUID(), UUID.randomUUID())).isZero();

    verifyNoInteractions(publisher, audit);
  }

  @Test
  void twoHoldersAreLockedInOneGlobalNumericOrderEvenWhenHolderOrderDisagrees() {
    UUID retired = new UUID(0, 4);
    UUID canonical = new UUID(0, 5);
    UUID location = new UUID(0, 3);
    UUID first = new UUID(0, 1);
    String oldKey = StockLot.key(null, retired, location, null, false);
    String newKey = StockLot.key(null, canonical, location, null, false);
    long firstMaximum =
        Math.max(
            InventoryStockCommands.lotLockKey(first, oldKey),
            InventoryStockCommands.lotLockKey(first, newKey));
    UUID second =
        java.util.stream.IntStream.rangeClosed(2, 100)
            .mapToObj(value -> new UUID(0, value))
            .filter(holder ->
                Math.min(
                        InventoryStockCommands.lotLockKey(holder, oldKey),
                        InventoryStockCommands.lotLockKey(holder, newKey))
                    < firstMaximum)
            .findFirst()
            .orElseThrow();
    var firstLot = lot(first, location);
    var secondLot = lot(second, location);
    when(repository.lots(retired, canonical)).thenReturn(List.of(firstLot, secondLot));
    when(inventoryRepository.tryLockExchangeLot(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(true);
    var participant =
        new InventoryItemReassignment(repository, inventoryRepository, audit, publisher);

    participant.reassignGameItem(retired, canonical);

    ArgumentCaptor<Long> keys = ArgumentCaptor.captor();
    org.mockito.Mockito.verify(inventoryRepository, org.mockito.Mockito.times(4))
        .tryLockExchangeLot(keys.capture());
    assertThat(keys.getAllValues()).isSorted();
    List<Long> expected =
        new ArrayList<>(
            List.of(
                InventoryStockCommands.lotLockKey(first, oldKey),
                    InventoryStockCommands.lotLockKey(first, newKey),
                InventoryStockCommands.lotLockKey(second, oldKey),
                    InventoryStockCommands.lotLockKey(second, newKey)));
    Collections.sort(expected);
    assertThat(keys.getAllValues()).containsExactlyElementsOf(expected);
  }

  @Test
  void anAdvisoryLockFailureOccursBeforeAnyReferenceOrAuditMutation() {
    UUID retired = UUID.randomUUID();
    UUID canonical = UUID.randomUUID();
    var existingLot = lot(UUID.randomUUID(), UUID.randomUUID());
    when(repository.lots(retired, canonical)).thenReturn(List.of(existingLot));
    when(inventoryRepository.tryLockExchangeLot(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(true, false);
    var participant =
        new InventoryItemReassignment(repository, inventoryRepository, audit, publisher);

    org.junit.jupiter.api.Assertions.assertThrows(
        GameItemReconciliationBusyException.class,
        () -> participant.reassignGameItem(retired, canonical));

    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
        .stock(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(inventoryRepository, org.mockito.Mockito.never())
        .lockExchangeLot(org.mockito.ArgumentMatchers.anyLong());
    verifyNoInteractions(audit, publisher);
  }

  private static InventoryItemReassignmentRepository.ReassignmentLot lot(
      UUID holder, UUID location) {
    var lot = org.mockito.Mockito.mock(InventoryItemReassignmentRepository.ReassignmentLot.class);
    when(lot.getMemberId()).thenReturn(holder);
    when(lot.getLocationId()).thenReturn(location);
    return lot;
  }
}
