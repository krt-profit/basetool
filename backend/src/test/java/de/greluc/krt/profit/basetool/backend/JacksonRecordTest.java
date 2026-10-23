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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import org.junit.jupiter.api.Test;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import tools.jackson.databind.json.JsonMapper;

public class JacksonRecordTest {
  @Test
  public void testSpel() throws Exception {
    MaterialDto dto =
        new MaterialDto(
            null, "Test", "RAW", "SCU", "desc", null, null, true, true, true, false, false, false,
            true, 1L);
    JsonMapper mapper = JsonMapper.builder().build();
    String json = mapper.writeValueAsString(dto);

    assertThat(json).contains("\"isVolatileQt\":true").contains("\"isIllegal\":true");
    ExpressionParser parser = new SpelExpressionParser();
    assertThat(parser.parseExpression("isVolatileQt").getValue(dto, Boolean.class)).isTrue();
    assertThat(parser.parseExpression("isIllegal").getValue(dto, Boolean.class)).isTrue();
  }
}
