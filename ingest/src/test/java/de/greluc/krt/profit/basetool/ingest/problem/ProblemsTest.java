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

package de.greluc.krt.profit.basetool.ingest.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Pins the byte order of a problem's {@code errors[]} entries, which the golden answers record. */
class ProblemsTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void aFieldErrorWritesItsPointerBeforeItsMessage() {
    assertThat(mapper.writeValueAsString(new Problems.FieldError("/orders", "violates minItems")))
        .isEqualTo("{\"pointer\":\"/orders\",\"message\":\"violates minItems\"}");
  }

  @Test
  void severalFieldErrorsKeepTheirOrderAndTheirMembersOrder() {
    List<Problems.FieldError> errors =
        List.of(new Problems.FieldError("/b", "second"), new Problems.FieldError("/a", "first"));
    assertThat(mapper.writeValueAsString(errors))
        .isEqualTo(
            "[{\"pointer\":\"/b\",\"message\":\"second\"},"
                + "{\"pointer\":\"/a\",\"message\":\"first\"}]");
  }
}
