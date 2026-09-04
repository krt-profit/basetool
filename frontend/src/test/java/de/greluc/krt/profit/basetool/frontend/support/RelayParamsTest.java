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

package de.greluc.krt.profit.basetool.frontend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link RelayParams} — the checks every request parameter passes before the frontend
 * relays it into a backend URI (REQ-SEC-051). The cases that matter are the hostile ones: a value
 * carrying {@code &}, {@code ?}, {@code #} or {@code /} must not survive, because downstream it is
 * concatenated into a path segment or dropped into a query builder.
 */
class RelayParamsTest {

  @Test
  @DisplayName("uuidOrNull parses a well-formed identifier and strips surrounding whitespace")
  void uuidOrNull_parsesWellFormedIdentifier() {
    UUID expected = UUID.fromString("11111111-2222-3333-4444-555555555555");

    assertEquals(expected, RelayParams.uuidOrNull("11111111-2222-3333-4444-555555555555"));
    assertEquals(expected, RelayParams.uuidOrNull("  11111111-2222-3333-4444-555555555555  "));
  }

  @ParameterizedTest
  @DisplayName("uuidOrNull rejects anything that is not a UUID, hostile URI syntax included")
  @ValueSource(
      strings = {
        "",
        "   ",
        "auth0|abc123",
        "../../etc/passwd",
        "11111111-2222-3333-4444-555555555555/../admin",
        "11111111-2222-3333-4444-555555555555?role=ADMIN",
        "11111111-2222-3333-4444-555555555555#fragment",
      })
  void uuidOrNull_rejectsNonUuid(String raw) {
    assertNull(RelayParams.uuidOrNull(raw));
  }

  @Test
  @DisplayName("uuidOrNull maps a null identifier to null")
  void uuidOrNull_mapsNullToNull() {
    assertNull(RelayParams.uuidOrNull(null));
  }

  @Test
  @DisplayName("oneOfOrNull keeps an allowed value and drops everything else")
  void oneOfOrNull_keepsOnlyAllowedValues() {
    List<String> allowed = List.of("SCWIKI", "UEX");

    assertEquals("UEX", RelayParams.oneOfOrNull("UEX", allowed));
    assertNull(RelayParams.oneOfOrNull("uex", allowed));
    assertNull(RelayParams.oneOfOrNull("UEX&size=9999", allowed));
    assertNull(RelayParams.oneOfOrNull("", allowed));
    assertNull(RelayParams.oneOfOrNull(null, allowed));
  }

  @Test
  @DisplayName("sortSpecOrNull accepts a property with or without a direction")
  void sortSpecOrNull_acceptsWellFormedSpecifications() {
    assertEquals("productName", RelayParams.sortSpecOrNull("productName"));
    assertEquals("productName,asc", RelayParams.sortSpecOrNull("productName,asc"));
    assertEquals("item.name,DESC", RelayParams.sortSpecOrNull("item.name,DESC"));
  }

  @ParameterizedTest
  @DisplayName("sortSpecOrNull rejects a specification that could open a second query parameter")
  @ValueSource(
      strings = {
        "",
        "   ",
        "name,sideways",
        "name&size=9999",
        "name#",
        "name?x=1",
        "name/../admin",
        "name,asc,desc",
      })
  void sortSpecOrNull_rejectsMalformedSpecifications(String raw) {
    assertNull(RelayParams.sortSpecOrNull(raw));
  }

  @Test
  @DisplayName("sortSpecOrNull maps a null specification to null")
  void sortSpecOrNull_mapsNullToNull() {
    assertNull(RelayParams.sortSpecOrNull(null));
  }
}
