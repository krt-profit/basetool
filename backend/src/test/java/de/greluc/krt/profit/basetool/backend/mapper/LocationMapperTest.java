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

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class LocationMapperTest {

  private final LocationMapper mapper = Mappers.getMapper(LocationMapper.class);

  @Test
  void toDto_shouldMapExposedFields() {
    UUID id = UUID.randomUUID();
    Location entity = new Location();
    entity.setId(id);
    entity.setName("Port Olisar");
    entity.setDescription("Crusader station");
    entity.setHidden(false);
    entity.setHomeLocation(true);
    entity.setVersion(3L);

    LocationDto dto = mapper.toDto(entity);

    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals("Port Olisar", dto.name());
    assertEquals("Crusader station", dto.description());
    assertFalse(dto.hidden());
    assertTrue(dto.homeLocation());
    assertEquals(3L, dto.version());
  }

  @Test
  void toEntity_shouldMapExposedFields() {
    UUID id = UUID.randomUUID();
    LocationDto dto = new LocationDto(id, "Lorville", "Hurston city", true, true, 1L);

    Location entity = mapper.toEntity(dto);

    assertNotNull(entity);
    assertEquals(id, entity.getId());
    assertEquals("Lorville", entity.getName());
    assertEquals("Hurston city", entity.getDescription());
    assertTrue(entity.getHidden());
    assertTrue(entity.getHomeLocation());
    assertEquals(1L, entity.getVersion());
    assertNull(entity.getCity());
    assertNull(entity.getSpaceStation());
  }

  @Test
  void stripServerManaged_shouldClearIdAndVersion() {
    Location entity = new Location();
    entity.setId(UUID.randomUUID());
    entity.setVersion(5L);
    entity.setName("Untainted");

    Location stripped = LocationMapper.stripServerManaged(entity);

    assertSame(entity, stripped, "stripServerManaged must mutate and return the same instance");
    assertNull(stripped.getId());
    assertNull(stripped.getVersion());
    assertEquals("Untainted", stripped.getName());
  }

  @Test
  void stripServerManaged_shouldHandleNullEntity() {
    assertNull(LocationMapper.stripServerManaged(null));
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toEntity(null));
  }
}
