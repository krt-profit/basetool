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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncFanout;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Two exchange writes touching the same rooms in one transaction send one frame per room. */
@ExtendWith(MockitoExtension.class)
class ExchangeLiveSyncCoalescingTest {

  @Mock private LiveSyncFanout fanout;

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void twoStockChangesInOneTransactionSendOneFramePerRoom() {
    ExchangeLiveSync liveSync =
        new ExchangeLiveSync(new LiveSyncPublisher(fanout, new SimpleMeterRegistry()));
    TransactionSynchronizationManager.initSynchronization();

    liveSync.stockChanged(true);
    liveSync.stockChanged(true);
    verify(fanout, never()).publish(any(), anyList());

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);

    verify(fanout, times(1))
        .publish(LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), List.of("stock"));
    verify(fanout, times(1))
        .publish(LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), List.of("board"));
    verify(fanout, times(2)).publish(any(), anyList());
  }
}
