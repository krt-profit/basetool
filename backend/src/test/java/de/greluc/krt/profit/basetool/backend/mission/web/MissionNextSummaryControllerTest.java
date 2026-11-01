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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionListDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests of {@link MissionNextSummaryController}: the row of the mission {@code /next} names,
 * flagged with the caller's sign-up from one read, and 204 when none is upcoming (REQ-MISSION-031).
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
  void noUpcomingMission_answers204AndReadsNothingFurther() {
    when(missionService.getNextMission()).thenReturn(Optional.empty());

    ResponseEntity<MissionListDto> response = controller.getNextMissionSummary();

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    verify(missionService, never()).signedUpMissionIds(any());
  }

  @Test
  void upcomingMission_isMappedWithItsCountAndTheCallersSignUp() {
    Mission upcoming = new Mission();
    upcoming.setId(UUID.randomUUID());
    MissionListDto row =
        new MissionListDto(
            upcoming.getId(),
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
            2L,
            true,
            0L);
    when(missionService.getNextMission()).thenReturn(Optional.of(upcoming));
    when(missionService.registeredCounts(List.of(upcoming.getId())))
        .thenReturn(Map.of(upcoming.getId(), 2L));
    when(missionService.signedUpMissionIds(List.of(upcoming.getId())))
        .thenReturn(Set.of(upcoming.getId()));
    when(missionMapper.toListDto(upcoming, 2L, true)).thenReturn(row);

    ResponseEntity<MissionListDto> response = controller.getNextMissionSummary();

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(row);
  }
}
