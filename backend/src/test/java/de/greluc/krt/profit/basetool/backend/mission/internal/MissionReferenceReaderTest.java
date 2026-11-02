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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapperImpl;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MissionReferenceReader}. */
@ExtendWith(MockitoExtension.class)
class MissionReferenceReaderTest {

  private static final Instant START = Instant.parse("2026-10-01T18:00:00Z");

  @Mock private MissionRepository missionRepository;
  @Mock private MissionVisibility missionVisibility;

  private MissionReferenceReader reader;

  @BeforeEach
  void setUp() {
    MissionMapper mapper = new MissionMapperImpl(null, null, null, null);
    reader = new MissionReferenceReader(missionRepository, missionVisibility, mapper);
  }

  private static Mission mission(UUID id, String name) {
    Mission mission = new Mission();
    mission.setId(id);
    mission.setName(name);
    mission.setStatus("PLANNED");
    mission.setPlannedStartTime(START);
    return mission;
  }

  @Test
  void aReadableMission_comesBackWithNameAndStart_andAnUnknownOneIsAbsent() {
    UUID known = UUID.randomUUID();
    UUID unknown = UUID.randomUUID();
    Mission readable = mission(known, "Op Sunfire");
    when(missionRepository.findAllById(Set.of(known, unknown))).thenReturn(List.of(readable));
    when(missionVisibility.canSeeMission(readable)).thenReturn(true);

    Map<UUID, MissionReferenceDto> found = reader.references(Set.of(known, unknown));

    assertEquals(Set.of(known), found.keySet());
    assertEquals("Op Sunfire", found.get(known).name());
    assertEquals(START, found.get(known).plannedStartTime());
  }

  @Test
  void anUnreadableMission_comesBackWithoutNameAndStart() {
    UUID hidden = UUID.randomUUID();
    Mission foreign = mission(hidden, "Internal Op");
    when(missionRepository.findAllById(Set.of(hidden))).thenReturn(List.of(foreign));
    when(missionVisibility.canSeeMission(foreign)).thenReturn(false);

    Map<UUID, MissionReferenceDto> found = reader.references(Set.of(hidden));

    assertEquals(new MissionReferenceDto(hidden, null, "PLANNED", null), found.get(hidden));
  }

  @Test
  void noIds_askNoRepository() {
    assertTrue(reader.references(Set.of()).isEmpty());

    verifyNoInteractions(missionRepository, missionVisibility);
  }
}
