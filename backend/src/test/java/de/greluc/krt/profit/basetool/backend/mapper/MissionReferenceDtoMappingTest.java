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

package de.greluc.krt.profit.basetool.backend.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Ensures that {@link MissionMapper#toReferenceDto(Mission)} exposes {@code plannedStartTime} as
 * UTC {@link Instant}, so dropdowns can render "Name - Date" (locale-formatted, date-only) on the
 * view layer.
 */
class MissionReferenceDtoMappingTest {

  // None of the mappers MissionMapper uses is reached by these mappings.
  private final MissionMapper mapper = new MissionMapperImpl(null, null, null, null);

  @Test
  void shouldMapPlannedStartTimeToReferenceDto() {
    // Given
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setName("Operation Orange Sun");
    Instant utc = Instant.parse("2026-05-12T09:30:00Z");
    mission.setPlannedStartTime(utc);

    // When
    MissionReferenceDto dto = mapper.toReferenceDto(mission);

    // Then
    assertNotNull(dto);
    assertEquals("Operation Orange Sun", dto.name());
    assertEquals(utc, dto.plannedStartTime(), "plannedStartTime must be preserved as UTC Instant");
  }

  @Test
  void shouldAllowNullPlannedStartTime() {
    Mission mission = new Mission();
    mission.setId(UUID.randomUUID());
    mission.setName("Undated Mission");

    MissionReferenceDto dto = mapper.toReferenceDto(mission);

    assertNotNull(dto);
    assertEquals("Undated Mission", dto.name());
    assertNull(dto.plannedStartTime());
  }
}
