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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient.MissionSearch;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MissionQuickAccessSearchContributor}: the mission list's scoped read. */
@ExtendWith(MockitoExtension.class)
class MissionQuickAccessSearchContributorTest {

  @Mock private MissionBackendClient missionClient;

  @InjectMocks private MissionQuickAccessSearchContributor contributor;

  /** It searches missions. */
  @Test
  void searchesMissions() {
    assertThat(contributor.kind()).isEqualTo(QuickAccessSearchKind.MISSION);
  }

  /** The term and limit go to the list read across every period and status. */
  @Test
  void asksTheListReadOverEveryPeriod() {
    when(missionClient.searchMissions(any()))
        .thenReturn(new PageResponse<>(List.of(), 0, 5, 0, 0, List.of()));

    contributor.results("mining", 5);

    ArgumentCaptor<MissionSearch> search = ArgumentCaptor.forClass(MissionSearch.class);
    verify(missionClient).searchMissions(search.capture());
    assertThat(search.getValue())
        .isEqualTo(new MissionSearch("mining", null, null, 0, 5, List.of(), "ALL"));
  }

  /** Each mission becomes a hit on its detail page with its status and planned start. */
  @Test
  void mapsMissionsToDetailHits() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    Instant start = Instant.parse("2026-11-01T18:00:00Z");
    when(missionClient.searchMissions(any()))
        .thenReturn(new PageResponse<>(List.of(mission(id, start)), 0, 5, 1, 1, List.of()));

    assertThat(contributor.results("mining", 5))
        .containsExactly(
            new QuickAccessHit(
                "/missions/00000000-0000-0000-0000-000000000001",
                "Mining run",
                "mission.status.planned",
                start));
  }

  /** A response without a body yields no hit. */
  @Test
  void noBodyYieldsNoHit() {
    when(missionClient.searchMissions(any())).thenReturn(null);

    assertThat(contributor.results("mining", 5)).isEmpty();
  }

  private static MissionListDto mission(UUID id, Instant start) {
    return new MissionListDto(
        id,
        "Mining run",
        null,
        null,
        "PLANNED",
        null,
        start,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        0L,
        false,
        1L);
  }
}
