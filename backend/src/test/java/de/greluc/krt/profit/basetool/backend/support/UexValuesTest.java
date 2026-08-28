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

package de.greluc.krt.profit.basetool.backend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.greluc.krt.profit.basetool.backend.support.UexValues.CrewRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UexValues#parseCrew(String)}.
 *
 * <p>The parser exists because UEX serves a vehicle's crew complement as one compact string and
 * <em>not</em> as the {@code crew_min} / {@code crew_max} fields this project used to bind — those
 * decoded to {@code null} and cleared both columns on every sync (REQ-DATA-015, ADR-0148). These
 * cases are the shapes the live {@code /vehicles} payload actually contains ({@code "1"}, {@code
 * "1,2"}, {@code "1,1"}, {@code ""} and absent), plus the malformed ones the parser must refuse.
 */
class UexValuesTest {

  @Test
  @DisplayName("a single number means an exact crew, so both bounds are filled")
  void singleValue_fillsBothBounds() {
    assertEquals(new CrewRange(1, 1), UexValues.parseCrew("1"));
    assertEquals(new CrewRange(8, 8), UexValues.parseCrew("8"));
  }

  @Test
  @DisplayName("a comma pair is a min/max range")
  void pair_isSplitIntoMinAndMax() {
    assertEquals(new CrewRange(1, 2), UexValues.parseCrew("1,2"));
    assertEquals(new CrewRange(1, 1), UexValues.parseCrew("1,1"));
    assertEquals(new CrewRange(4, 8), UexValues.parseCrew(" 4 , 8 "));
  }

  @Test
  @DisplayName("a reversed pair never yields a max below its min")
  void reversedPair_isNormalised() {
    // Not seen in the live feed, but a max below the min would be a nonsense range to store and
    // would read as a data-entry error rather than as the upstream typo it is.
    assertEquals(new CrewRange(3, 3), UexValues.parseCrew("3,1"));
  }

  @Test
  @DisplayName("absent, blank and unparseable crews leave BOTH bounds null")
  void unparseable_yieldsNoBounds() {
    // Half a range is not a fact UEX stated: "1 to ?" must not be stored as a crew of 1.
    for (String raw : new String[] {null, "", "   ", "x", "1,x", "1,", ","}) {
      CrewRange range = UexValues.parseCrew(raw);
      assertNull(range.min(), "min for '" + raw + "'");
      assertNull(range.max(), "max for '" + raw + "'");
    }
  }

  @Test
  @DisplayName("bounds past the second are ignored rather than failing the whole value")
  void extraBounds_areIgnored() {
    assertEquals(new CrewRange(2, 5), UexValues.parseCrew("2,5,9"));
  }
}
