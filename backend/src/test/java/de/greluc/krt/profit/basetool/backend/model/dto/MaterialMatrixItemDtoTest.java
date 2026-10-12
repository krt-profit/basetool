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

package de.greluc.krt.profit.basetool.backend.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MaterialMatrixItemDto;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests for {@link MaterialMatrixItemDto}. */
public class MaterialMatrixItemDtoTest {

  /** Verifies the DTO round-trips through Jackson and exposes the effective {@code planetName}. */
  @Test
  public void testSerialization() throws Exception {
    MaterialMatrixItemDto dto =
        new MaterialMatrixItemDto(
            UUID.randomUUID(),
            "Material",
            false,
            false,
            false,
            new MaterialCategoryDto(UUID.randomUUID(), "Metal", 0L),
            UUID.randomUUID(),
            "Terminal",
            "Nickname",
            "System",
            BigDecimal.TEN,
            BigDecimal.ONE,
            "City",
            "Station",
            "Outpost",
            "Hurston",
            true,
            true,
            true);
    JsonMapper mapper = JsonMapper.builder().build();
    String json = mapper.writeValueAsString(dto);
    assertNotNull(json);
    assertTrue(json.contains("\"planetName\":\"Hurston\""));
    assertEquals("Hurston", dto.planetName());
  }
}
