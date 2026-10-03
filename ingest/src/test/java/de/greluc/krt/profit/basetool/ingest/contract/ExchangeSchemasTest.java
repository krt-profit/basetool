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

package de.greluc.krt.profit.basetool.ingest.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The gateway's schema checks agree with the conformance fixtures (REQ-XCH-011, REQ-XCH-026). */
class ExchangeSchemasTest {

  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final ExchangeSchemas schemas = new ExchangeSchemas(new ExchangeDocuments(), mapper);

  @Test
  void everyFixtureIsJudgedAsItsFolderSays() throws IOException {
    int checked = 0;
    try (Stream<Path> targets = Files.list(EXAMPLES)) {
      for (Path target : targets.filter(Files::isDirectory).sorted().toList()) {
        String folder = target.getFileName().toString();
        int split = folder.indexOf("--");
        String schema =
            split < 0
                ? folder + ".schema.json"
                : folder.substring(0, split) + ".schema.json#/$defs/" + folder.substring(split + 2);
        for (Path fixture : jsonFiles(target.resolve("valid"))) {
          JsonNode node = read(fixture);
          assertThat(schemas.validate(schema, node)).as("valid " + fixture).isEmpty();
          assertThat(schemas.unknownFields(schema, node)).as("unknown in " + fixture).isEmpty();
          checked++;
        }
        for (Path fixture : jsonFiles(target.resolve("invalid"))) {
          assertThat(schemas.validate(schema, read(fixture))).as("invalid " + fixture).isNotEmpty();
          checked++;
        }
      }
    }
    assertThat(checked).isGreaterThanOrEqualTo(90);
  }

  @Test
  void aViolationPointsAtTheOffendingValue() {
    JsonNode request =
        mapper.readTree(
            "{\"kind\":\"ITEM\",\"refs\":[{\"name\":\"ok\"},{\"name\":\""
                + "x".repeat(201)
                + "\"}]}");

    assertThat(schemas.validate("resolve-request.schema.json", request))
        .extracting(ExchangeSchemas.Violation::pointer)
        .contains("/refs/1/name");
  }

  @Test
  void undeclaredFieldsAreFoundAtAnyDepthButExtensionsStayOpen() {
    JsonNode request =
        mapper.readTree(
            "{\"kind\":\"ITEM\",\"futureFlag\":true,\"refs\":[{\"name\":\"a\",\"colour\":\"red\","
                + "\"extensions\":{\"org.example.x\":{\"anything\":1}}},{\"bt\":\"b\"}]}");

    assertThat(schemas.validate("resolve-request.schema.json", request)).isEmpty();
    assertThat(schemas.unknownFields("resolve-request.schema.json", request))
        .containsExactlyInAnyOrder("/futureFlag", "/refs/0/colour");
  }

  @Test
  void aFieldDeclaredInSeveralBranchesIsWalkedAlongAllOfThem() {
    JsonNode page =
        mapper.readTree(
            "{\"items\":[{\"key\":\"k\",\"surplus\":true,\"material\":{\"bt\":\"a\",\"odd\":1}}],"
                + "\"removed\":[],\"hasMore\":false}");

    assertThat(schemas.unknownFields("page.schema.json#/$defs/stockPage", page))
        .containsExactlyInAnyOrder("/items/0/surplus", "/items/0/material/odd");
    assertThat(schemas.unknownFields("page.schema.json", page)).isEmpty();
    assertThat(schemas.unknownFields("resolve-request.schema.json", mapper.readTree("\"text\"")))
        .isEmpty();
  }

  @Test
  void pointerTokensAreEscaped() {
    assertThat(ExchangeSchemas.escape("a/b~c")).isEqualTo("a~1b~0c");
  }

  @Test
  void aPointerTooLongToReportIsShortenedToItsLongestFittingAncestor() {
    String fitting = "/ops/0/" + "a".repeat(193);

    assertThat(ExchangeSchemas.reportable(fitting)).isEqualTo(fitting);
    assertThat(ExchangeSchemas.reportable(fitting + "b")).isEqualTo("/ops/0");
    assertThat(ExchangeSchemas.reportable("/" + "c".repeat(300))).isEmpty();
  }

  @Test
  void anUnknownSchemaIsAProgrammingError() {
    JsonNode empty = mapper.readTree("{}");

    assertThatThrownBy(() -> schemas.validate("nope.schema.json", empty))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> schemas.unknownFields("nope.schema.json", empty))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Lists the JSON files of a folder.
   *
   * @param folder the folder
   * @return the files, sorted; empty when the folder does not exist
   * @throws IOException if listing fails
   */
  private static List<Path> jsonFiles(Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(folder)) {
      return files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
    }
  }

  /**
   * Reads a fixture.
   *
   * @param fixture the file
   * @return its root
   * @throws IOException if it cannot be read
   */
  private JsonNode read(Path fixture) throws IOException {
    return mapper.readTree(Files.readString(fixture));
  }
}
