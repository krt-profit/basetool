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

import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCategoryDto;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class MaterialCategoryMapperTest {

  private final MaterialCategoryMapper mapper = Mappers.getMapper(MaterialCategoryMapper.class);

  @Test
  void toDto_shouldMapAllFields() {
    UUID id = UUID.randomUUID();
    MaterialCategory entity = new MaterialCategory();
    entity.setId(id);
    entity.setName("Mineral");
    entity.setVersion(2L);

    MaterialCategoryDto dto = mapper.toDto(entity);

    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals("Mineral", dto.name());
    assertEquals(2L, dto.version());
  }

  @Test
  void toEntity_shouldIgnoreCreatedAtAndUpdatedAt() {
    UUID id = UUID.randomUUID();
    MaterialCategoryDto dto = new MaterialCategoryDto(id, "Gas", 1L);

    MaterialCategory entity = mapper.toEntity(dto);

    assertNotNull(entity);
    assertEquals(id, entity.getId());
    assertEquals("Gas", entity.getName());
    assertEquals(1L, entity.getVersion());
    assertNull(entity.getCreatedAt());
    assertNull(entity.getUpdatedAt());
  }

  @Test
  void toEntity_shouldNotCopyAuditTimestampsEvenIfDtoHadThem() {
    MaterialCategoryDto dto = new MaterialCategoryDto(UUID.randomUUID(), "Composite", 1L);
    MaterialCategory entity = mapper.toEntity(dto);

    Instant before = entity.getUpdatedAt();
    MaterialCategory entity2 = mapper.toEntity(dto);
    assertEquals(before, entity2.getUpdatedAt());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toEntity(null));
  }
}
