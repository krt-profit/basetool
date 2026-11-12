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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.inventory.api.MissionEarmark;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Lager's mission earmark targets (REQ-INV-027, REQ-MISSION-008): the lookup of the write and
 * undo paths keeps every mission's name and planned start without asking who may read it, the
 * caller's view keeps them only for a mission the caller may read.
 */
class MissionEarmarkTargetsTest {

  private static final Instant START = Instant.parse("2026-11-20T19:00:00Z");

  private final MissionRepository missionRepository = mock(MissionRepository.class);
  private final MissionVisibility missionVisibility = mock(MissionVisibility.class);
  private final MissionEarmarkTargets targets =
      new MissionEarmarkTargets(missionRepository, missionVisibility);

  private static Mission mission(String name) {
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setName(name);
    mission.setPlannedStartTime(START);
    return mission;
  }

  @Test
  void theLookupKeepsTheNameAndStartOfAMissionTheCallerMayNotReadWithoutAskingTheRule() {
    UUID internal = UUID.randomUUID();
    MissionEarmark earmark =
        new MissionEarmark(internal, "Interner Einsatz der anderen Staffel", START);
    when(missionRepository.findEarmarks(Set.of(internal))).thenReturn(List.of(earmark));

    Map<UUID, MissionEarmark> earmarks = targets.missions(Set.of(internal));

    assertThat(earmarks.get(internal)).isEqualTo(earmark);
    verifyNoInteractions(missionVisibility);
    verify(missionRepository, never()).findAllById(anyCollection());
  }

  @Test
  void theCallersViewKeepsAReadableMissionsNameAndStartAndAnUnreadableOnesIdAlone() {
    Mission open = mission("Erzlauf");
    Mission internal = mission("Interner Einsatz der anderen Staffel");
    when(missionRepository.findAllById(Set.of(open.getId(), internal.getId())))
        .thenReturn(List.of(open, internal));
    when(missionVisibility.canSeeMission(open)).thenReturn(true);
    when(missionVisibility.canSeeMission(internal)).thenReturn(false);

    Map<UUID, MissionEarmark> earmarks =
        targets.shownMissions(Set.of(open.getId(), internal.getId()));

    assertThat(earmarks.get(open.getId()))
        .isEqualTo(new MissionEarmark(open.getId(), "Erzlauf", START));
    assertThat(earmarks.get(internal.getId()))
        .isEqualTo(new MissionEarmark(internal.getId(), null, null));
  }

  @Test
  void anUnknownMissionIsAbsentFromBoth() {
    UUID unknown = UUID.randomUUID();
    when(missionRepository.findEarmarks(Set.of(unknown))).thenReturn(List.of());
    when(missionRepository.findAllById(Set.of(unknown))).thenReturn(List.of());

    assertThat(targets.missions(Set.of(unknown))).isEmpty();
    assertThat(targets.shownMissions(Set.of(unknown))).isEmpty();
  }

  @Test
  void noIdsReadNothing() {
    assertThat(targets.missions(Set.of())).isEmpty();
    assertThat(targets.shownMissions(Set.of())).isEmpty();
    verify(missionRepository, never()).findEarmarks(anyCollection());
    verify(missionRepository, never()).findAllById(anyCollection());
  }
}
