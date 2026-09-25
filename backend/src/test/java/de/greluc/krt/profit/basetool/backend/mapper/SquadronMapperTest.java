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

import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class SquadronMapperTest {

  private final SquadronMapper mapper = Mappers.getMapper(SquadronMapper.class);

  @Test
  void toDto_and_back_shouldPreserveFields() {
    Squadron s = new Squadron();
    s.setId(UUID.randomUUID());
    s.setName("Vanguard");
    s.setShorthand("VAN");
    s.setDescription("Test squad");

    SquadronDto dto = mapper.toDto(s);
    assertNotNull(dto);
    assertEquals(s.getId(), dto.id());
    assertEquals("Vanguard", dto.name());
    assertEquals("VAN", dto.shorthand());
    assertEquals("Test squad", dto.description());

    Squadron back = mapper.toEntity(dto);
    assertEquals(dto.id(), back.getId());
    assertEquals(dto.name(), back.getName());
    assertEquals(dto.shorthand(), back.getShorthand());
    assertEquals(dto.description(), back.getDescription());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toEntity(null));
  }

  @Test
  void orgUnitToReferenceDto_shouldProjectSquadronOwner() {
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setName("IRIDIUM");
    squadron.setShorthand("IRI");

    SquadronReferenceDto ref = mapper.orgUnitToReferenceDto(squadron);

    assertNotNull(ref);
    assertEquals(squadron.getId(), ref.id());
    assertEquals("IRIDIUM", ref.name());
    assertEquals("IRI", ref.shorthand());
  }

  @Test
  void orgUnitToReferenceDto_shouldProjectSpecialCommandOwner() {
    SpecialCommand sk = new SpecialCommand();
    sk.setId(UUID.randomUUID());
    sk.setName("Special Command Alpha");
    sk.setShorthand("SKA");

    SquadronReferenceDto ref = mapper.orgUnitToReferenceDto(sk);

    assertNotNull(ref);
    assertEquals(sk.getId(), ref.id());
    assertEquals("Special Command Alpha", ref.name());
    assertEquals("SKA", ref.shorthand());
  }

  @Test
  void orgUnitToReferenceDto_shouldReturnNull_whenOrgUnitNull() {
    assertNull(mapper.orgUnitToReferenceDto(null));
  }
}
