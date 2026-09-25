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

import de.greluc.krt.profit.basetool.backend.model.Announcement;
import de.greluc.krt.profit.basetool.backend.model.dto.AnnouncementDto;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class AnnouncementMapperTest {

  private final AnnouncementMapper mapper = Mappers.getMapper(AnnouncementMapper.class);

  @Test
  void toDto_shouldMapAllFields() {
    UUID id = UUID.randomUUID();
    Instant updatedAt = Instant.parse("2026-05-13T10:15:30Z");
    Announcement entity = new Announcement();
    entity.setId(id);
    entity.setContent("Server maintenance tonight");
    entity.setUpdatedAt(updatedAt);
    entity.setVersion(7L);

    AnnouncementDto dto = mapper.toDto(entity);

    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals("Server maintenance tonight", dto.content());
    assertEquals(updatedAt, dto.updatedAt());
    assertEquals(7L, dto.version());
  }

  @Test
  void toDto_withNullFields_shouldPassThroughNulls() {
    Announcement entity = new Announcement();

    AnnouncementDto dto = mapper.toDto(entity);

    assertNotNull(dto);
    assertNull(dto.id());
    assertNull(dto.content());
    assertNull(dto.updatedAt());
    assertNull(dto.version());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
  }
}
