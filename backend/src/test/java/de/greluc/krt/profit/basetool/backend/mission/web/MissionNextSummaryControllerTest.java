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

package de.greluc.krt.profit.basetool.backend.mission.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionListDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests of {@link MissionNextSummaryController}: the bounded list of the caller's upcoming
 * missions as rows flagged with the caller's sign-up from one batched read, an empty list when none
 * is upcoming, and the server-side limit bounds (REQ-MISSION-031).
 */
@ExtendWith(MockitoExtension.class)
class MissionNextSummaryControllerTest {

  @Mock private MissionService missionService;
  @Mock private MissionMapper missionMapper;

  private MissionNextSummaryController controller;

  @BeforeEach
  void setUp() {
    controller = new MissionNextSummaryController(missionService, missionMapper);
  }

  @Test
  void noUpcomingMission_answersAnEmptyListAndReadsNothingFurther() {
    when(missionService.getUpcomingOwnAndSignedUp(5)).thenReturn(List.of());

    List<MissionListDto> rows = controller.getNextMissionsSummary(null);

    assertThat(rows).isEmpty();
    verify(missionService, never()).signedUpMissionIds(any());
  }

  @Test
  void upcomingMissions_areMappedInServiceOrderWithTheirCountAndTheCallersSignUp() {
    Mission first = mission();
    Mission second = mission();
    MissionListDto firstRow = row(first, 2L, true);
    MissionListDto secondRow = row(second, 0L, false);
    when(missionService.getUpcomingOwnAndSignedUp(3)).thenReturn(List.of(first, second));
    List<UUID> ids = List.of(first.getId(), second.getId());
    when(missionService.registeredCounts(ids)).thenReturn(Map.of(first.getId(), 2L));
    when(missionService.signedUpMissionIds(ids)).thenReturn(Set.of(first.getId()));
    when(missionMapper.toListDto(first, 2L, true)).thenReturn(firstRow);
    when(missionMapper.toListDto(second, 0L, false)).thenReturn(secondRow);

    List<MissionListDto> rows = controller.getNextMissionsSummary(3);

    assertThat(rows).containsExactly(firstRow, secondRow);
  }

  @Test
  void aMissingLimitUsesTheDefaultOfFive() {
    when(missionService.getUpcomingOwnAndSignedUp(5)).thenReturn(List.of());

    controller.getNextMissionsSummary(null);

    verify(missionService).getUpcomingOwnAndSignedUp(5);
  }

  @Test
  void theBoundsOneAndTenAreAccepted() {
    when(missionService.getUpcomingOwnAndSignedUp(anyInt())).thenReturn(List.of());

    controller.getNextMissionsSummary(1);
    controller.getNextMissionsSummary(10);

    verify(missionService).getUpcomingOwnAndSignedUp(1);
    verify(missionService).getUpcomingOwnAndSignedUp(10);
  }

  @Test
  void aLimitOutsideOneToTenIsRejectedBeforeAnyRead() {
    assertThatThrownBy(() -> controller.getNextMissionsSummary(0))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> controller.getNextMissionsSummary(11))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> controller.getNextMissionsSummary(-3))
        .isInstanceOf(BadRequestException.class);

    verify(missionService, never()).getUpcomingOwnAndSignedUp(anyInt());
  }

  private static Mission mission() {
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    return mission;
  }

  private static MissionListDto row(Mission mission, long registered, boolean signedUp) {
    return new MissionListDto(
        mission.getId(),
        "Op",
        null,
        null,
        "PLANNED",
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        registered,
        signedUp,
        0L);
  }
}
