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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.service.LiveSyncRelayService;
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

  private final LiveSyncRelayService relayService;

  /**
   * Refreshes the member's own hangar after the commit.
   *
   * @param member the member
   */
  public void hangarChanged(@NotNull UUID member) {
    afterCommit(personal(LiveSyncTopicClass.HANGAR_OWN, member), SHIPS);
  }

  /**
   * Refreshes the member's own blueprints after the commit.
   *
   * @param member the member
   */
  public void blueprintsChanged(@NotNull UUID member) {
    afterCommit(personal(LiveSyncTopicClass.BLUEPRINTS_OWN, member), BLUEPRINT_LIST);
  }

  /**
   * Refreshes the Lager pages after the commit, and the Materialbörse board when offers changed.
   *
   * @param offersChanged whether a book-out lowered or removed an offer
   */
  public void stockChanged(boolean offersChanged) {
    afterCommit(global(LiveSyncTopicClass.INVENTORY_ALL), STOCK);
    if (offersChanged) {
      afterCommit(global(LiveSyncTopicClass.MATERIALBOARD), BOARD);
    }
  }

  /**
   * Builds a member's personal room.
   *
   * @param topicClass the room's class
   * @param member the member
   * @return the room
   */
  private static @NotNull LiveSyncTopic personal(
      @NotNull LiveSyncTopicClass topicClass, @NotNull UUID member) {
    return new LiveSyncTopic(topicClass, member, topicClass.prefix() + ':' + member);
  }

  /**
   * Builds a global room.
   *
   * @param topicClass the room's class
   * @return the room
   */
  private static @NotNull LiveSyncTopic global(@NotNull LiveSyncTopicClass topicClass) {
    return new LiveSyncTopic(topicClass, null, topicClass.prefix());
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
   * Publishes one frame; a failed publish only delays a page's refresh and never fails the write.
   *
   * @param topic the room
   * @param section the section
   */
  private void publish(@NotNull LiveSyncTopic topic, @NotNull String section) {
    try {
      relayService.publishFromServer(topic, List.of(section));
    } catch (RuntimeException e) {
      log.warn(
          "Live-sync frame for {} after an exchange write was not published",
          topic.topicClass().metricLabel(),
          e);
    }
  }
}
