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

import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.dto.ManufacturerDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class ManufacturerMapperTest {

  private final ManufacturerMapper mapper = Mappers.getMapper(ManufacturerMapper.class);

  @Test
  void toDto_shouldMapAllFields() {
    // Given
    UUID id = UUID.randomUUID();
    Manufacturer entity = new Manufacturer();
    entity.setId(id);
    entity.setName("Roberts Space Industries");
    entity.setAbbreviation("RSI");
    entity.setNickname("Roberts");
    entity.setWiki("https://wiki.example.com/rsi");
    entity.setDescription("Founding manufacturer");
    entity.setHidden(true);

    // When
    ManufacturerDto dto = mapper.toDto(entity);

    // Then
    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals("Roberts Space Industries", dto.name());
    assertEquals("RSI", dto.abbreviation());
    assertEquals("Roberts", dto.nickname());
    assertEquals("https://wiki.example.com/rsi", dto.wiki());
    assertEquals("Founding manufacturer", dto.description());
    assertTrue(dto.hidden());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
  }
}
