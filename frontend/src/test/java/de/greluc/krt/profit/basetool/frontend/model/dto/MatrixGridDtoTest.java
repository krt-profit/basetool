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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Locks the JSON property names of {@link MatrixGridDto} to exactly what the browser grid ({@code
 * /js/materials-matrix.js}) reads. The boolean components ({@code isIllegal}, {@code
 * hasLoadingDock}, …) carry {@code @JsonProperty} precisely because Jackson would otherwise strip
 * the {@code is}/{@code has} prefix and emit {@code illegal} / {@code loadingDock}, silently
 * breaking the client at render time (the build would still pass). This test fails fast if anyone
 * removes those annotations or renames a field out of sync with the script.
 */
class MatrixGridDtoTest {

  /** Serializes a representative grid and asserts the wire key names the client depends on. */
  @Test
  void serializesWithTheKeyNamesTheClientReads() {
    MatrixGridDto grid =
        new MatrixGridDto(
            List.of(
                new MatrixGridDto.Column(
                    "T1", "T1", "Stanton", "Hurston", "planet-hurston", true, false)),
            List.of(new MatrixGridDto.SystemGroup("Stanton", 1)),
            List.of(
                new MatrixGridDto.Group(
                    "Metals",
                    List.of(
                        new MatrixGridDto.Row(
                            "Gold",
                            true,
                            false,
                            true,
                            Map.of(
                                "T1",
                                new MatrixGridDto.Cell(
                                    new BigDecimal("5"), new BigDecimal("7"))))))));

    String json = JsonMapper.builder().build().writeValueAsString(grid);

    for (String key :
        List.of(
            "\"terminals\"",
            "\"systemGroups\"",
            "\"groups\"",
            "\"prices\"",
            "\"planetCssClass\"",
            "\"hasLoadingDock\"",
            "\"isAutoLoad\"",
            "\"isIllegal\"",
            "\"isVolatileQt\"",
            "\"isVolatileTime\"",
            "\"priceBuy\"",
            "\"priceSell\"")) {
      assertTrue(json.contains(key), "expected JSON key " + key + " in: " + json);
    }

    assertFalse(json.contains("\"illegal\""), "isIllegal must not be stripped to 'illegal'");
    assertFalse(json.contains("\"loadingDock\""), "hasLoadingDock must not be stripped");
    assertFalse(json.contains("\"autoLoad\""), "isAutoLoad must not be stripped to 'autoLoad'");
  }
}
