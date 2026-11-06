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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiDimensionDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Verifies the distinct geometry contracts in public Wiki item responses. */
class ScWikiDimensionDtoTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void physicalCargoAndDisplayDimensionsRemainDistinct() {
    ScWikiDimensionDto dimension =
        MAPPER.readValue(
            """
            {"width":0.75,"height":0.75,"length":1.5,"volume":0.019,
             "dimensions":{"width":0.072,"height":0.238,"length":1.184},
             "cargo_dimension":{"width":0.06,"height":0.34,"length":1},
             "ui_dimension":{"width":0.75,"height":0.75,"length":1.5}}
            """,
            ScWikiDimensionDto.class);

    assertEquals(0.072, dimension.width());
    assertEquals(0.238, dimension.height());
    assertEquals(1.184, dimension.length());
    assertEquals(0.06, dimension.cargo().width());
    assertEquals(0.75, dimension.ui().width());
    assertEquals(0.019, dimension.volumeScu());
  }

  @Test
  void absentPhysicalDimensionsDoNotBecomeDisplayDimensions() {
    ScWikiDimensionDto dimension =
        MAPPER.readValue(
            """
            {"width":0.75,"height":0.75,"length":0.75,
             "ui_dimension":{"width":0.75,"height":0.75,"length":0.75}}
            """,
            ScWikiDimensionDto.class);

    assertNull(dimension.width());
    assertNull(dimension.height());
    assertNull(dimension.length());
    assertEquals(0.75, dimension.ui().width());
  }
}
