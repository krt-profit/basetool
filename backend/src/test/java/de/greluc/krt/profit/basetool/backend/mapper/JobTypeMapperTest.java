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

import de.greluc.krt.profit.basetool.backend.catalogue.api.JobType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.JobTypeMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class JobTypeMapperTest {

  private final JobTypeMapper mapper = Mappers.getMapper(JobTypeMapper.class);

  @Test
  void toDto_shouldMapParentIdAndFields() {
    JobType parent = new JobType();
    parent.setId(UUID.randomUUID());

    JobType jt = new JobType();
    jt.setId(UUID.randomUUID());
    jt.setName("Pilot");
    jt.setDescription("Flies");
    jt.setArchetype(JobTypeArchetype.CREW);
    jt.setParent(parent);

    JobTypeDto dto = mapper.toDto(jt);
    assertNotNull(dto);
    assertEquals(jt.getId(), dto.id());
    assertEquals("Pilot", dto.name());
    assertEquals("Flies", dto.description());
    assertEquals(JobTypeArchetype.CREW, dto.archetype());
    assertEquals(parent.getId(), dto.parentId());
  }

  @Test
  void toDto_withNullParent_shouldSetParentIdNull() {
    JobType jt = new JobType();
    jt.setId(UUID.randomUUID());
    jt.setName("Engineer");
    jt.setArchetype(JobTypeArchetype.CREW);
    jt.setParent(null);

    JobTypeDto dto = mapper.toDto(jt);
    assertNull(dto.parentId());
  }

  @Test
  void toEntity_shouldCreateShallowParent_whenParentIdProvided() {
    UUID parentId = UUID.randomUUID();
    JobTypeDto dto =
        new JobTypeDto(
            UUID.randomUUID(),
            "Child",
            null,
            JobTypeArchetype.CREW,
            parentId,
            true,
            false,
            false,
            null);

    JobType entity = mapper.toEntity(dto);
    assertNotNull(entity);
    assertEquals(dto.id(), entity.getId());
    assertEquals(dto.name(), entity.getName());
    assertEquals(dto.description(), entity.getDescription());
    assertEquals(dto.archetype(), entity.getArchetype());
    assertNotNull(entity.getParent());
    assertEquals(parentId, entity.getParent().getId());
  }

  @Test
  void toEntity_withNullParentId_shouldSetParentNull() {
    JobTypeDto dto =
        new JobTypeDto(
            UUID.randomUUID(), "Solo", null, JobTypeArchetype.CREW, null, true, false, false, null);
    JobType entity = mapper.toEntity(dto);
    assertNull(entity.getParent());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toEntity(null));
  }
}
