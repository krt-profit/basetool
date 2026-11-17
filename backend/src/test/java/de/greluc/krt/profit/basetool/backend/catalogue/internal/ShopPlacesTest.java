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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kStarmapObjectDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The places a shop's terminal is looked for at, nearest first (REQ-P4K-043). */
class ShopPlacesTest {

  private StarmapTreeBuilder builder;
  private UUID daymar;
  private UUID station;
  private UUID hangar;

  @BeforeEach
  void setUp() {
    builder = new StarmapTreeBuilder();
    UUID stanton = builder.add("Stanton", "Star", null);
    UUID crusader = builder.add("Crusader", "Planet", stanton);
    daymar = builder.add("Daymar", "Moon", crusader);
    station = builder.add("Seraphim Station", "Manmade", crusader);
    hangar = builder.add("Seraphim Hangar", "Manmade", station);
  }

  private UUID id(UUID guid) {
    return builder.object(guid).getId();
  }

  private static P4kStarmapObjectDto placedBy(UUID guid, String entityName) {
    return new P4kStarmapObjectDto(
        guid.toString(), null, null, null, null, null, null, null, null, entityName);
  }

  @Test
  void aPlace_isFollowedByItsAncestors_upToTheFirstCelestialBody() {
    ShopPlaces places = new ShopPlaces(builder.objects(), List.of());

    assertThat(places.scopes(id(hangar), List.of())).containsExactly(id(hangar), id(station));
  }

  @Test
  void aCelestialPlace_standsAlone() {
    ShopPlaces places = new ShopPlaces(builder.objects(), List.of());

    assertThat(places.scopes(id(daymar), List.of())).containsExactly(id(daymar));
  }

  @Test
  void aContainerPlacingAnObjectBelowThePlace_refinesIt() {
    UUID outpost = builder.add("Shubin SCD-1", "Outpost", daymar);
    ShopPlaces places =
        new ShopPlaces(
            builder.objects(),
            List.of(placedBy(daymar, "Stanton2b_Daymar"), placedBy(outpost, "Shubin_SCD1")));

    assertThat(places.scopes(id(daymar), List.of("OOC_Stanton", "stanton2b_daymar", "SHUBIN_SCD1")))
        .containsExactly(id(outpost));
  }

  @Test
  void aContainerPlacingAnObjectElsewhere_refinesNothing() {
    UUID outpost = builder.add("Shubin SCD-1", "Outpost", daymar);
    ShopPlaces places =
        new ShopPlaces(builder.objects(), List.of(placedBy(outpost, "Shubin_SCD1")));

    assertThat(places.scopes(id(hangar), List.of("Shubin_SCD1")))
        .containsExactly(id(hangar), id(station));
  }
}
