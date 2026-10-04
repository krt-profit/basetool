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

import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipTypeDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Unit tests for {@link ShipTypeMapper}. */
class ShipTypeMapperTest {

  private final ShipTypeMapper mapper =
      new ShipTypeMapperImpl(Mappers.getMapper(ManufacturerMapper.class));

  @Test
  void toDto_shouldMapManufacturerNested() {
    Manufacturer mfr = new Manufacturer();
    mfr.setId(UUID.randomUUID());
    mfr.setName("Anvil");

    ShipType type = new ShipType();
    type.setId(UUID.randomUUID());
    type.setName("Carrack");
    type.setManufacturer(mfr);
    type.setScu(456);

    ShipTypeDto dto = mapper.toDto(type);

    assertNotNull(dto);
    assertEquals(type.getId(), dto.id());
    assertEquals("Carrack", dto.name());
    assertEquals(456, dto.scu());
    assertNotNull(dto.manufacturer());
    assertEquals("Anvil", dto.manufacturer().name());
  }

  @Test
  void toDto_sourcesDescriptionFromRichColumns_germanPreferred() {
    ShipType german = new ShipType();
    german.setName("Carrack");
    german.setDescriptionDe("Deutsche Beschreibung");
    german.setDescriptionEn("English description");
    assertEquals("Deutsche Beschreibung", mapper.toDto(german).description());
  }

  @Test
  void toDto_fallsBackToEnglishDescription_whenGermanNull() {
    ShipType englishOnly = new ShipType();
    englishOnly.setName("Gladius");
    englishOnly.setDescriptionEn("English only");
    assertEquals("English only", mapper.toDto(englishOnly).description());
  }

  @Test
  void toDto_returnsNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
  }
}
