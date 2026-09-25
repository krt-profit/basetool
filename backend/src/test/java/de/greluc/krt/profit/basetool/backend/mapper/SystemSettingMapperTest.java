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

import de.greluc.krt.profit.basetool.backend.model.SystemSetting;
import de.greluc.krt.profit.basetool.backend.model.dto.SystemSettingDto;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class SystemSettingMapperTest {

  private final SystemSettingMapper mapper = Mappers.getMapper(SystemSettingMapper.class);

  @Test
  void toDto_shouldMapKeyValueAndVersion() {
    SystemSetting setting =
        SystemSetting.builder().id("feature.flag.combat").value("ENABLED").build();
    setting.setVersion(3L);

    SystemSettingDto dto = mapper.toDto(setting);

    assertNotNull(dto);
    assertEquals("feature.flag.combat", dto.id());
    assertEquals("ENABLED", dto.value());
    assertEquals(3L, dto.version());
  }

  @Test
  void toDto_withNullSource_shouldReturnNull() {
    assertNull(mapper.toDto(null));
  }
}
