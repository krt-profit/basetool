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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the anonymised game-log blueprint corpus that blueprint name resolution is measured
 * against: it must parse through the real import parser into exactly 31 distinct blueprints, and it
 * must never carry personal data from the logs it was built from.
 */
class BlueprintCorpusFixtureTest {

  private static final String FIXTURE = "/fixtures/blueprint-corpus/game-log-corpus-v1.json";
  private static final int EXPECTED_EVENTS = 31;
  private static final Set<String> FORBIDDEN_KEYS =
      Set.of("sourceFolder", "sourceFile", "additionalSourceFolders", "geid", "accountId");

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void parsesIntoThirtyOneDistinctBlueprints() throws IOException {
    List<BlueprintExportParser.ParsedEntry> entries =
        BlueprintExportParser.parse(mapper, fixtureAsUpload());

    assertEquals(EXPECTED_EVENTS, entries.size());
    Set<String> names = new HashSet<>();
    for (BlueprintExportParser.ParsedEntry entry : entries) {
      assertNotNull(entry.suggestedAcquiredAt(), entry.externalName());
      names.add(entry.externalName());
    }
    assertEquals(EXPECTED_EVENTS, names.size());
  }

  @Test
  void keepsNamesVerbatimIncludingPackTagsAndNoBreakSpace() throws IOException {
    List<String> names =
        BlueprintExportParser.parse(mapper, fixtureAsUpload()).stream()
            .map(BlueprintExportParser.ParsedEntry::externalName)
            .toList();

    assertTrue(names.contains("[E-S3] CF-337 Panther \"Hazard-Zone\" Repeater"));
    assertTrue(names.contains("Oracle Helmet"));
  }

  @Test
  void carriesNoPersonalData() throws IOException {
    JsonNode root = readFixture();

    assertEquals(EXPECTED_EVENTS, root.get("blueprintCount").asInt());
    assertEquals(1, root.get("players").size());
    assertEquals("PLAYER_A", root.get("players").get(0).get("handle").asString());
    for (JsonNode blueprint : root.get("blueprints")) {
      assertEquals("PLAYER_A", blueprint.get("player").asString());
      assertTrue(blueprint.get("receivedAt").asString().startsWith("2026-01-0"));
      for (String key : blueprint.propertyNames()) {
        assertFalse(FORBIDDEN_KEYS.contains(key), key);
      }
    }
    for (String key : root.propertyNames()) {
      assertFalse(FORBIDDEN_KEYS.contains(key), key);
    }
  }

  private MockMultipartFile fixtureAsUpload() throws IOException {
    try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
      assertNotNull(in, FIXTURE);
      return new MockMultipartFile("file", "game-log-corpus-v1.json", "application/json", in);
    }
  }

  private JsonNode readFixture() throws IOException {
    try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
      assertNotNull(in, FIXTURE);
      return mapper.readTree(in);
    }
  }
}
