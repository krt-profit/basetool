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

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Announces a P4K write that changed what catalogue readers see, an APPLY or a review resolution
 * (REQ-DATA-011, REQ-P4K-013): it evicts the caches a P4K write can make stale and publishes the
 * global {@link LiveSyncTopicClass#CATALOGUE} event, on which the frontend evicts its own catalogue
 * caches and open views refresh.
 *
 * <p>Inside a transaction both happen after the commit, eviction first, and a rolled-back write
 * announces nothing; outside one they happen at once.
 */
@Component
@RequiredArgsConstructor
public class P4kCatalogueChange {

  /** The one section of the global catalogue room. */
  static final String CATALOGUE_SECTION = "catalogue";

  private final MasterDataCacheEvictionService cacheEvictionService;
  private final LiveSyncPublisher publisher;

  /** Evicts the P4K-written caches, then publishes the catalogue event. */
  public void announce() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      evictAndPublish();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            evictAndPublish();
          }
        });
  }

  /** Evicts before publishing, so a receiver that re-reads on the event reads past the caches. */
  private void evictAndPublish() {
    cacheEvictionService.evictP4kSyncedMasterData();
    publisher.publish(
        LiveSyncTopic.global(LiveSyncTopicClass.CATALOGUE), List.of(CATALOGUE_SECTION));
  }
}
