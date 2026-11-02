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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.MissionAllocationDto;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobOrderMissionAllocationRedactor}: slices of unreadable missions lose name
 * and start, readable ones stay, and a row without slices costs no lookup.
 */
class JobOrderMissionAllocationRedactorTest {

  private static final Instant START = Instant.parse("2030-01-02T03:04:05Z");

  private final MissionRepository missionRepository = mock(MissionRepository.class);
  private final MissionVisibility missionVisibility = mock(MissionVisibility.class);
  private final JobOrderMissionAllocationRedactor redactor =
      new JobOrderMissionAllocationRedactor(missionRepository, missionVisibility);

  @Test
  void anUnreadableMissionKeepsIdAndAmountButLosesNameAndStart() {
    Mission hidden = mission();
    Mission shown = mission();
    when(missionRepository.findAllById(Set.of(hidden.getId(), shown.getId())))
        .thenReturn(List.of(hidden, shown));
    when(missionVisibility.canSeeMission(hidden)).thenReturn(false);
    when(missionVisibility.canSeeMission(shown)).thenReturn(true);
    InventoryItemDto row =
        row(
            new MissionAllocationDto(hidden.getId(), "Secret", START, 4.0),
            new MissionAllocationDto(shown.getId(), "Open", START, 2.0));

    List<MissionAllocationDto> slices =
        redactor.anonymiseUnreadableMissions(List.of(row)).getFirst().missionAllocations();

    assertThat(slices)
        .containsExactly(
            new MissionAllocationDto(hidden.getId(), null, null, 4.0),
            new MissionAllocationDto(shown.getId(), "Open", START, 2.0));
  }

  @Test
  void aMissionTheRepositoryNoLongerKnowsIsAnonymisedToo() {
    UUID gone = UUID.randomUUID();
    when(missionRepository.findAllById(Set.of(gone))).thenReturn(List.of());

    List<MissionAllocationDto> slices =
        redactor
            .anonymiseUnreadableMissions(
                List.of(row(new MissionAllocationDto(gone, "Ghost", START, 1.0))))
            .getFirst()
            .missionAllocations();

    assertThat(slices).containsExactly(new MissionAllocationDto(gone, null, null, 1.0));
  }

  @Test
  void rowsWithoutMissionSlicesAreReturnedUnchangedWithoutALookup() {
    List<InventoryItemDto> rows = List.of(row());

    assertThat(redactor.anonymiseUnreadableMissions(rows)).isSameAs(rows);
    verifyNoInteractions(missionRepository, missionVisibility);
  }

  private static Mission mission() {
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    return mission;
  }

  private static InventoryItemDto row(MissionAllocationDto... slices) {
    return new InventoryItemDto(
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        500,
        10.0,
        false,
        false,
        List.of(),
        0.0,
        List.of(slices),
        0.0,
        null,
        null,
        1L,
        false,
        Instant.EPOCH);
  }
}
