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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncRelayService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/** The frames an exchange write raises, and that they wait for the commit (REQ-XCH-013). */
@ExtendWith(MockitoExtension.class)
class ExchangeLiveSyncTest {

  private static final UUID MEMBER = UUID.fromString("0b7a3c6e-2f1d-4e8a-9c3b-5d6e7f809a1b");

  @Mock private LiveSyncRelayService relayService;
  @InjectMocks private ExchangeLiveSync liveSync;

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void aShipWriteRefreshesOnlyTheMembersOwnHangarOnceCommitted() {
    TransactionSynchronizationManager.initSynchronization();

    liveSync.hangarChanged(MEMBER);
    verify(relayService, never()).publishFromServer(any(), anyList());

    TransactionSynchronizationUtils.triggerAfterCommit();
    verify(relayService)
        .publishFromServer(eq(LiveSyncTopic.parse("hangar:" + MEMBER)), eq(List.of("ships")));
  }

  @Test
  void aRolledBackWriteRaisesNoFrame() {
    TransactionSynchronizationManager.initSynchronization();

    liveSync.blueprintsChanged(MEMBER);
    TransactionSynchronizationManager.clearSynchronization();

    verify(relayService, never()).publishFromServer(any(), anyList());
  }

  @Test
  void aStockWriteRefreshesTheLagerAndTheBoardOnlyWhenOffersChanged() {
    liveSync.stockChanged(false);
    verify(relayService)
        .publishFromServer(eq(LiveSyncTopic.parse("inventory")), eq(List.of("stock")));
    verify(relayService, never())
        .publishFromServer(eq(LiveSyncTopic.parse("materialboard")), anyList());

    liveSync.stockChanged(true);
    verify(relayService)
        .publishFromServer(eq(LiveSyncTopic.parse("materialboard")), eq(List.of("board")));
  }

  @Test
  void aFailedPublishDoesNotFailTheWrite() {
    doThrow(new IllegalStateException("redis down"))
        .when(relayService)
        .publishFromServer(any(), anyList());

    liveSync.blueprintsChanged(MEMBER);

    verify(relayService)
        .publishFromServer(eq(LiveSyncTopic.parse("blueprints:" + MEMBER)), eq(List.of("list")));
  }
}
