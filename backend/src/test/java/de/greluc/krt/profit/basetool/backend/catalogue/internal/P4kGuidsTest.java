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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The one GUID parser of the catalogue sources reads the canonical form only. */
class P4kGuidsTest {

  private static final String CANONICAL = "339bd641-8dfb-4bc1-b273-5ffc5f702fbb";

  @Test
  void theCanonicalForm_parses_inAnyCase_andAroundBlanks() {
    UUID expected = UUID.fromString(CANONICAL);

    assertThat(P4kGuids.parse(CANONICAL)).isEqualTo(expected);
    assertThat(P4kGuids.parse(" " + CANONICAL.toUpperCase(Locale.ROOT) + " ")).isEqualTo(expected);
  }

  @Test
  void shortGroups_blanksAndNull_readAsNoGuid() {
    assertThat(P4kGuids.parse("1-2-3-4-5")).isNull();
    assertThat(P4kGuids.parse("339bd641-8dfb-4bc1-b273")).isNull();
    assertThat(P4kGuids.parse("  ")).isNull();
    assertThat(P4kGuids.parse(null)).isNull();
  }

  @Test
  void theUexParser_followsTheSameRule() {
    assertThat(UexValues.parseUuid("1-2-3-4-5")).isNull();
    assertThat(UexValues.parseUuid(CANONICAL)).isEqualTo(UUID.fromString(CANONICAL));
  }
}
