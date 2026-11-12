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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargets;
import de.greluc.krt.profit.basetool.backend.inventory.api.MissionEarmark;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Lager's per-transaction earmark lookup (REQ-INV-027): the write and undo paths look a mission
 * up through the mission module's read that needs no caller, a chip shows what the caller is shown,
 * and within one transaction each is read once without either answer reaching the other.
 */
class InventoryEarmarkTargetCacheTest {

  private static final Instant START = Instant.parse("2026-11-20T19:00:00Z");

  private final EarmarkTargets.Missions missions = mock(EarmarkTargets.Missions.class);
  private final InventoryEarmarkTargetCache cache =
      new InventoryEarmarkTargetCache(
          missions,
          mock(EarmarkTargets.JobOrders.class),
          mock(EarmarkTargets.Donations.class),
          mock(EntityManager.class));

  private final UUID missionId = UUID.randomUUID();
  private final MissionEarmark full = new MissionEarmark(missionId, "Interner Einsatz", START);

  @AfterEach
  void endTheTransactionSynchronization() {
    TransactionSynchronizationManager.unbindResourceIfPossible(cache);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void theWriteAndUndoLookupNeverAsksWhatTheCallerIsShown() {
    when(missions.missions(Set.of(missionId))).thenReturn(Map.of(missionId, full));

    assertThat(cache.mission(missionId)).isEqualTo(full);
    verify(missions, never()).shownMissions(anyCollection());
  }

  @Test
  void aChipShowsWhatTheCallerIsShownAndNeverAsksTheWriteAndUndoLookup() {
    when(missions.shownMissions(Set.of(missionId)))
        .thenReturn(Map.of(missionId, full.withoutDetails()));

    assertThat(cache.shownMission(missionId)).isEqualTo(new MissionEarmark(missionId, null, null));
    verify(missions, never()).missions(anyCollection());
  }

  @Test
  void withinOneTransactionEachViewIsReadOnceAndNeitherAnswerReachesTheOther() {
    TransactionSynchronizationManager.initSynchronization();
    when(missions.missions(Set.of(missionId))).thenReturn(Map.of(missionId, full));
    when(missions.shownMissions(Set.of(missionId)))
        .thenReturn(Map.of(missionId, full.withoutDetails()));

    assertThat(cache.mission(missionId)).isEqualTo(full);
    assertThat(cache.shownMission(missionId)).isEqualTo(full.withoutDetails());
    assertThat(cache.mission(missionId)).isEqualTo(full);
    assertThat(cache.shownMission(missionId)).isEqualTo(full.withoutDetails());

    verify(missions).missions(anyCollection());
    verify(missions).shownMissions(anyCollection());
  }

  @Test
  void anUnknownMissionIsAbsentFromBothViews() {
    when(missions.missions(Set.of(missionId))).thenReturn(Map.of());
    when(missions.shownMissions(Set.of(missionId))).thenReturn(Map.of());

    assertThat(cache.mission(missionId)).isNull();
    assertThat(cache.shownMission(missionId)).isNull();
  }
}
