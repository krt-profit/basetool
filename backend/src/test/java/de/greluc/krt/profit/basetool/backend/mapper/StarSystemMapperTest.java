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

import de.greluc.krt.profit.basetool.backend.model.StarSystem;
import de.greluc.krt.profit.basetool.backend.model.dto.StarSystemDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class StarSystemMapperTest {

  private final StarSystemMapper mapper = Mappers.getMapper(StarSystemMapper.class);

  @Test
  void toDto_shouldMapAllFields() {
    UUID id = UUID.randomUUID();
    StarSystem entity = new StarSystem();
    entity.setId(id);
    entity.setIdSystem(42);
    entity.setName("Stanton");
    entity.setDescription("UEE system");
    entity.setIsAvailableLive(true);
    entity.setWiki("https://example.org/stanton");
    entity.setJurisdictionName("UEE");
    entity.setFactionName("Empire");
    entity.setVersion(3L);

    StarSystemDto dto = mapper.toDto(entity);

    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals(42, dto.idSystem());
    assertEquals("Stanton", dto.name());
    assertEquals("UEE system", dto.description());
    assertTrue(dto.isAvailableLive());
    assertEquals("https://example.org/stanton", dto.wiki());
    assertEquals("UEE", dto.jurisdictionName());
    assertEquals("Empire", dto.factionName());
    assertEquals(3L, dto.version());
  }

  @Test
  void toEntity_shouldMapAllFields() {
    UUID id = UUID.randomUUID();
    StarSystemDto dto =
        new StarSystemDto(
            id, 99, "Pyro", "Lawless", false, "https://example.org/pyro", "Lawless", "Pirates", 1L);

    StarSystem entity = mapper.toEntity(dto);

    assertNotNull(entity);
    assertEquals(id, entity.getId());
    assertEquals(99, entity.getIdSystem());
    assertEquals("Pyro", entity.getName());
    assertEquals("Lawless", entity.getDescription());
    assertEquals(false, entity.getIsAvailableLive());
    assertEquals("https://example.org/pyro", entity.getWiki());
    assertEquals("Lawless", entity.getJurisdictionName());
    assertEquals("Pirates", entity.getFactionName());
    assertEquals(1L, entity.getVersion());
  }

  @Test
  void roundtrip_shouldPreserveAllFields() {
    StarSystemDto dto =
        new StarSystemDto(
            UUID.randomUUID(), 1, "Sol", "Cradle of humanity", true, "wiki", "UEE", "Empire", 5L);

    StarSystem entity = mapper.toEntity(dto);
    StarSystemDto back = mapper.toDto(entity);

    assertEquals(dto, back);
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toEntity(null));
  }
}
