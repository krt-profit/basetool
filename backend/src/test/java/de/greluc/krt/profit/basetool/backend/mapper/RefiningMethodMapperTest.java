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

import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.dto.RefiningMethodDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class RefiningMethodMapperTest {

  private final RefiningMethodMapper mapper = Mappers.getMapper(RefiningMethodMapper.class);

  @Test
  void toDto_shouldMapAllFields() {
    UUID id = UUID.randomUUID();
    RefiningMethod entity = new RefiningMethod();
    entity.setId(id);
    entity.setName("Cormack");
    entity.setDescription("High yield, slow");
    entity.setCode("CORMACK");
    entity.setRatingYield(95);
    entity.setRatingCost(70);
    entity.setRatingSpeed(30);

    RefiningMethodDto dto = mapper.toDto(entity);

    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals("Cormack", dto.name());
    assertEquals("High yield, slow", dto.description());
    assertEquals("CORMACK", dto.code());
    assertEquals(95, dto.ratingYield());
    assertEquals(70, dto.ratingCost());
    assertEquals(30, dto.ratingSpeed());
  }

  @Test
  void toEntity_shouldMapAllFields() {
    UUID id = UUID.randomUUID();
    RefiningMethodDto dto =
        new RefiningMethodDto(id, "Dinyx Solventation", "Balanced", "DINYX", 50, 60, 70);

    RefiningMethod entity = mapper.toEntity(dto);

    assertNotNull(entity);
    assertEquals(id, entity.getId());
    assertEquals("Dinyx Solventation", entity.getName());
    assertEquals("Balanced", entity.getDescription());
    assertEquals("DINYX", entity.getCode());
    assertEquals(50, entity.getRatingYield());
    assertEquals(60, entity.getRatingCost());
    assertEquals(70, entity.getRatingSpeed());
  }

  @Test
  void roundtrip_shouldPreserveAllFields() {
    RefiningMethodDto original =
        new RefiningMethodDto(
            UUID.randomUUID(), "Pyrometric Chromalysis", null, "PYRO", 40, 30, 100);

    RefiningMethodDto back = mapper.toDto(mapper.toEntity(original));

    assertEquals(original, back);
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toEntity(null));
  }
}
