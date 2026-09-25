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

import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationDto;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class OperationMapperTest {

  private OperationMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new OperationMapperImpl(Mappers.getMapper(SquadronMapper.class));
  }

  @Test
  void toDto_shouldMapAllFields() {
    UUID id = UUID.randomUUID();
    Instant created = Instant.parse("2026-01-01T10:00:00Z");
    Instant updated = Instant.parse("2026-02-01T10:00:00Z");
    Operation entity = new Operation();
    entity.setId(id);
    entity.setName("Operation Sunfire");
    entity.setDescription("Strike package");
    entity.setStatus(OperationStatus.ACTIVE);
    entity.setVersion(5L);
    entity.setCreatedAt(created);
    entity.setUpdatedAt(updated);

    OperationDto dto = mapper.toDto(entity);

    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals("Operation Sunfire", dto.name());
    assertEquals("Strike package", dto.description());
    assertEquals(OperationStatus.ACTIVE, dto.status());
    assertEquals(5L, dto.version());
    assertEquals(created, dto.createdAt());
    assertEquals(updated, dto.updatedAt());
  }

  @Test
  void toEntity_fromCreateDto_shouldIgnoreIdAndTimestampsAndMissions() {
    OperationCreateDto create =
        new OperationCreateDto("Op Aurora", "Recon", OperationStatus.PLANNED, null);

    Operation entity = mapper.toEntity(create);

    assertNotNull(entity);
    assertEquals("Op Aurora", entity.getName());
    assertEquals("Recon", entity.getDescription());
    assertEquals(OperationStatus.PLANNED, entity.getStatus());
    assertNull(entity.getId());
    assertNull(entity.getCreatedAt());
    assertNull(entity.getUpdatedAt());
    assertNotNull(
        entity.getMissions(), "missions collection should be initialised by entity defaults");
    assertTrue(entity.getMissions().isEmpty());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toEntity(null));
  }
}
