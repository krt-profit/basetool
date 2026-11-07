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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A P4K write that readers see evicts the P4K-written caches and publishes the catalogue event
 * after its commit, and a rolled-back one does neither (REQ-DATA-011, REQ-P4K-013).
 */
@ExtendWith(MockitoExtension.class)
class P4kCatalogueChangeTest {

  /** The global catalogue room. */
  private static final LiveSyncTopic CATALOGUE = LiveSyncTopic.global(LiveSyncTopicClass.CATALOGUE);

  @Mock private MasterDataCacheEvictionService cacheEvictionService;
  @Mock private LiveSyncPublisher publisher;

  private P4kCatalogueChange change;

  @BeforeEach
  void setUp() {
    change = new P4kCatalogueChange(cacheEvictionService, publisher);
  }

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void insideATransaction_theCommitEvictsTheCachesAndThenPublishes() {
    TransactionSynchronizationManager.initSynchronization();

    change.announce();

    verifyNoInteractions(cacheEvictionService, publisher);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    InOrder order = inOrder(cacheEvictionService, publisher);
    order.verify(cacheEvictionService).evictP4kSyncedMasterData();
    order.verify(publisher).publish(CATALOGUE, List.of("catalogue"));
  }

  @Test
  void aRolledBackTransaction_evictsAndPublishesNothing() {
    TransactionSynchronizationManager.initSynchronization();

    change.announce();
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

    verifyNoInteractions(cacheEvictionService, publisher);
  }

  @Test
  void outsideATransaction_theCachesAreEvictedAndTheEventPublishedAtOnce() {
    change.announce();

    InOrder order = inOrder(cacheEvictionService, publisher);
    order.verify(cacheEvictionService).evictP4kSyncedMasterData();
    order.verify(publisher).publish(CATALOGUE, List.of("catalogue"));
  }
}
