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

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells the member's open pages that an exchange write changed their data (REQ-XCH-013): the frame
 * goes out once the write's transaction has committed, and never for a rolled-back one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeLiveSync {

  /** The section of the member's own hangar room. */
  static final String SHIPS = "ships";

  /** The section of the member's own blueprints room. */
  static final String BLUEPRINT_LIST = "list";

  /** The section of the shared Lager room. */
  static final String STOCK = "stock";

  /** The section of the Materialbörse room's offer board. */
  static final String BOARD = "board";

  private final LiveSyncPublisher publisher;

  /**
   * Refreshes the member's own hangar after the commit.
   *
   * @param member the member
   */
  public void hangarChanged(@NotNull UUID member) {
    afterCommit(LiveSyncTopic.of(LiveSyncTopicClass.HANGAR_OWN, member), SHIPS);
  }

  /**
   * Refreshes the member's own blueprints after the commit.
   *
   * @param member the member
   */
  public void blueprintsChanged(@NotNull UUID member) {
    afterCommit(LiveSyncTopic.of(LiveSyncTopicClass.BLUEPRINTS_OWN, member), BLUEPRINT_LIST);
  }

  /**
   * Refreshes the Lager pages after the commit, and the Materialbörse board when offers changed.
   *
   * @param offersChanged whether a book-out lowered or removed an offer
   */
  public void stockChanged(boolean offersChanged) {
    afterCommit(LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), STOCK);
    if (offersChanged) {
      afterCommit(LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), BOARD);
    }
  }

  /**
   * Publishes once the current transaction has committed, or at once outside a transaction.
   *
   * @param topic the room
   * @param section the section
   */
  private void afterCommit(@NotNull LiveSyncTopic topic, @NotNull String section) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      publish(topic, section);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            publish(topic, section);
          }
        });
  }

  /**
   * Publishes one frame.
   *
   * @param topic the room
   * @param section the section
   */
  private void publish(@NotNull LiveSyncTopic topic, @NotNull String section) {
    publisher.publish(topic, List.of(section));
  }
}
