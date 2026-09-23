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

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.model.dto.TerminalDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class TerminalMapperTest {

  private final TerminalMapper mapper = Mappers.getMapper(TerminalMapper.class);

  @Test
  void toDto_shouldMapExposedFields() {
    // Given — only the fields that TerminalDto actually exposes.
    UUID id = UUID.randomUUID();
    java.time.Instant syncedAt = java.time.Instant.parse("2026-05-16T12:34:56Z");
    Terminal entity = new Terminal();
    entity.setId(id);
    entity.setName("Area 18 Trade & Development Division");
    entity.setNickname("TDD A18");
    entity.setStarSystemName("Stanton");
    entity.setPlanetName("ArcCorp");
    entity.setCityName("Area18");
    entity.setSpaceStationName(null);
    entity.setHasLoadingDock(true);
    entity.setIsAutoLoad(false);
    entity.setHasLoadingDockOverridden(true);
    entity.setIsAutoLoadOverridden(false);
    entity.setUexHasLoadingDock(false);
    entity.setUexIsAutoLoad(true);
    entity.setUexSyncedAt(syncedAt);
    entity.setHidden(true);

    // When
    TerminalDto dto = mapper.toDto(entity);

    // Then
    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals("Area 18 Trade & Development Division", dto.name());
    assertEquals("TDD A18", dto.nickname());
    assertEquals("Stanton", dto.starSystemName());
    assertEquals("ArcCorp", dto.planetName());
    assertEquals("Area18", dto.cityName());
    assertNull(dto.spaceStationName());
    assertTrue(dto.hasLoadingDock());
    assertFalse(dto.isAutoLoad());
    assertTrue(dto.hasLoadingDockOverridden());
    assertFalse(dto.isAutoLoadOverridden());
    // The raw UEX mirror columns are exposed independently of the override flags so
    // the admin UI can show what UEX currently claims even while a pin is active.
    assertFalse(dto.uexHasLoadingDock());
    assertTrue(dto.uexIsAutoLoad());
    assertEquals(syncedAt, dto.uexSyncedAt());
    assertTrue(dto.hidden());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
  }
}
