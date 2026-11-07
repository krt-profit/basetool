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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * The type families of the UEX tables match the starmap types of the LIVE catalogue: each real
 * place in the trimmed fixture is accepted by the table UEX keeps it in (REQ-P4K-035).
 */
class StarmapPlaceKindTest {

  private static final Map<String, String> TYPE_BY_NAME = typesByName();

  private static Map<String, String> typesByName() {
    Map<String, String> types = new HashMap<>();
    for (JsonNode node : P4kTestCatalogues.tree().get("starmapObjects")) {
      JsonNode en = node.path("name").path("en");
      if (en.isString()) {
        types.putIfAbsent(en.asString(), node.path("type").asString());
      }
    }
    return types;
  }

  @ParameterizedTest
  @CsvSource({
    "Stanton, STAR_SYSTEM",
    "Hurston, PLANET",
    "Hurston, ORBIT",
    "Daymar, MOON",
    "Lorville, CITY",
    "Levski, CITY",
    "Everus Harbor, SPACE_STATION",
    "ARC-L1 Wide Forest Station, SPACE_STATION",
    "Shubin Mining Facility SCD-1, OUTPOST",
    "Lazarus Complex Tithonus-I, OUTPOST",
    "Stanton - Magnus Jump Point, ORBIT",
    "Pyro-Stanton Jump Point, ORBIT",
    "RSI Event Platform, POI",
    "The Icebreaker, POI",
    "Mining Claim #LX4-224, POI"
  })
  void aRealPlace_isAcceptedByItsUexTable(String name, StarmapPlaceKind kind) {
    assertThat(TYPE_BY_NAME).containsKey(name);
    assertThat(kind.accepts(TYPE_BY_NAME.get(name))).as(TYPE_BY_NAME.get(name)).isTrue();
  }

  @Test
  void everyTypeOfTheLiveCatalogue_isLinkableByAtLeastOneTable() {
    assertThat(TYPE_BY_NAME.values())
        .allSatisfy(type ->
            assertThat(Arrays.stream(StarmapPlaceKind.values()).anyMatch(k -> k.accepts(type)))
                .as(type)
                .isTrue());
  }

  @Test
  void aPlanetIsNoCity_andAMoonNoStation() {
    assertThat(StarmapPlaceKind.CITY.accepts("Planet")).isFalse();
    assertThat(StarmapPlaceKind.SPACE_STATION.accepts("Moon")).isFalse();
  }
}
