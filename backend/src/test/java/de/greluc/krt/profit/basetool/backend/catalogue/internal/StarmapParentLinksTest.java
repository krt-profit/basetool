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

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A parent name identifies a parent only while every row under it links to the same object. */
class StarmapParentLinksTest {

  private static final String SYSTEM = "Stanton";
  private static final UUID FIRST = UUID.fromString("5b000000-0000-4000-8000-000000000001");
  private static final UUID SECOND = UUID.fromString("5b000000-0000-4000-8000-000000000002");

  private static StarmapPlaceRow city(String name) {
    return new StarmapPlaceRow(
        StarmapPlaceKind.CITY,
        UUID.randomUUID(),
        name,
        SYSTEM,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false);
  }

  private static StarmapPlaceRow terminalIn(String city) {
    return new StarmapPlaceRow(
        StarmapPlaceKind.TERMINAL,
        UUID.randomUUID(),
        "Krt Terminal",
        SYSTEM,
        null,
        null,
        null,
        city,
        null,
        null,
        null,
        false);
  }

  @Test
  void aNameOneRowLinks_isTheChildsParent() {
    StarmapParentLinks<UUID> links = new StarmapParentLinks<>();
    links.put(city("Krt Lorville"), FIRST);

    assertThat(terminalIn("Krt Lorville").nearestLinkedParent(links.byKind())).isEqualTo(FIRST);
  }

  @Test
  void twoRowsOfOneNameLinkingTheSameObject_stayAParent() {
    StarmapParentLinks<UUID> links = new StarmapParentLinks<>();
    links.put(city("Krt Lorville"), FIRST);
    links.put(city("Krt Lorville"), FIRST);

    assertThat(terminalIn("Krt Lorville").nearestLinkedParent(links.byKind())).isEqualTo(FIRST);
  }

  @Test
  void twoRowsOfOneNameLinkingDifferentObjects_identifyNoParent() {
    StarmapParentLinks<UUID> links = new StarmapParentLinks<>();
    links.put(city("Krt Lorville"), FIRST);
    links.put(city("Krt Lorville"), SECOND);
    links.put(city("Krt Lorville"), FIRST);

    assertThat(terminalIn("Krt Lorville").nearestLinkedParent(links.byKind())).isNull();
  }
}
