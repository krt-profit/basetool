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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells the open unit hangar overviews that ships changed (ADR-0260): after the commit, one frame
 * to the room of every org unit the transaction touched and one to the all-units room, never for a
 * rolled-back write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HangarUnitLiveSync {

  /** The section every hangar overview room carries. */
  static final String SHIPS = "ships";

  private final LiveSyncPublisher publisher;

  /**
   * Announces a change of ships in the given org units; a {@code null} entry is a ship without a
   * unit, which only the all-units room hears.
   *
   * @param orgUnits the units whose overview changed
   */
  public void shipsChanged(@NotNull Collection<@Nullable OrgUnit> orgUnits) {
    unitsChanged(orgUnits.stream().filter(Objects::nonNull).map(OrgUnit::getId).toList());
  }

  /**
   * Announces a change of ships in the given org units, by id.
   *
   * @param orgUnitIds the ids of the units whose overview changed
   */
  public void unitsChanged(@NotNull Collection<UUID> orgUnitIds) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      publish(new LinkedHashSet<>(orgUnitIds));
      return;
    }
    Pending pending = (Pending) TransactionSynchronizationManager.getResource(this);
    if (pending == null) {
      pending = new Pending(new LinkedHashSet<>());
      TransactionSynchronizationManager.bindResource(this, pending);
      TransactionSynchronizationManager.registerSynchronization(pending.synchronization(this));
    }
    pending.units().addAll(orgUnitIds);
  }

  /**
   * Publishes one frame per unit room and one to the all-units room.
   *
   * @param units the distinct unit ids
   */
  private void publish(@NotNull Set<UUID> units) {
    for (UUID unit : units) {
      send(LiveSyncTopic.of(LiveSyncTopicClass.HANGAR_UNIT, unit));
    }
    send(LiveSyncTopic.global(LiveSyncTopicClass.HANGAR_UNITS));
  }

  /**
   * Publishes one frame; a failed publish only delays a page's refresh and never fails the write.
   *
   * @param topic the room
   */
  private void send(@NotNull LiveSyncTopic topic) {
    try {
      publisher.publish(topic, List.of(SHIPS));
    } catch (RuntimeException e) {
      log.warn(
          "Live-sync frame for {} after a hangar write was not published",
          topic.topicClass().metricLabel(),
          e);
    }
  }

  /**
   * The units one transaction has touched so far.
   *
   * @param units the collected unit ids
   */
  private record Pending(@NotNull Set<UUID> units) {

    /**
     * Builds the synchronization that publishes the collected units after the commit and releases
     * the transaction-bound collection either way.
     *
     * @param owner the publisher the collection is bound under
     * @return the synchronization
     */
    @NotNull
    TransactionSynchronization synchronization(@NotNull HangarUnitLiveSync owner) {
      return new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          owner.publish(units);
        }

        @Override
        public void afterCompletion(int status) {
          TransactionSynchronizationManager.unbindResourceIfPossible(owner);
        }
      };
    }
  }
}
