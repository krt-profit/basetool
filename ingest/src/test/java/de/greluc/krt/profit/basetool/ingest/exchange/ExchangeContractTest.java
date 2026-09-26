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

package de.greluc.krt.profit.basetool.ingest.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the exchange API v1 contract (REQ-XCH-011, REQ-XCH-025, REQ-XCH-026): every published schema
 * is a valid JSON Schema 2020-12 document at its permanent {@code $id}, every conformance fixture
 * passes or fails as its folder says, the OpenAPI document serves exactly the REQ-XCH-001 routes
 * and references only existing schemas, and the error registry holds unique codes.
 */
class ExchangeContractTest {

  private static final String BASE = "https://ingest.profit-base.online/exchange/v1/schemas/";

  private static final Path SCHEMAS = Path.of("src/main/resources/exchange/v1/schemas");

  private static final Path OPENAPI = Path.of("src/main/resources/api/exchange-v1.openapi.json");

  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");

  private static final Path ERRORS = Path.of("../docs/exchange/errors.md");

  private static final Set<String> ROUTES =
      Set.of(
          "/exchange/v1",
          "/exchange/v1/openapi.json",
          "/exchange/v1/schemas/{name}.schema.json",
          "/exchange/v1/me/installation",
          "/exchange/v1/me/account-check",
          "/exchange/v1/catalog/resolve",
          "/exchange/v1/catalog/locations",
          "/exchange/v1/me/blueprints",
          "/exchange/v1/me/blueprints/changes",
          "/exchange/v1/me/stock",
          "/exchange/v1/me/stock/changes",
          "/exchange/v1/me/ships",
          "/exchange/v1/me/ships/changes",
          "/exchange/v1/me/org-demand",
          "/exchange/v1/me/drafts/blueprints",
          "/exchange/v1/me/drafts/refinery-orders");

  private static final Pattern ERROR_ROW =
      Pattern.compile("^\\| `([A-Z][A-Z0-9_]+)` \\| (\\d{3}) \\|", Pattern.MULTILINE);

  private final JsonMapper mapper = JsonMapper.builder().build();

  private final SchemaRegistry registry =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder.schemaIdResolvers(
                  resolvers -> resolvers.mapPrefix(BASE, "classpath:exchange/v1/schemas/")));

  @Test
  void everySchemaSitsAtItsPermanentIdAndIsValidAgainstTheMetaSchema() throws IOException {
    Schema meta =
        registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    List<Path> files = schemaFiles();
    assertThat(files).hasSizeGreaterThanOrEqualTo(28);
    for (Path file : files) {
      String text = Files.readString(file, StandardCharsets.UTF_8);
      JsonNode node = mapper.readTree(text);
      assertThat(node.path("$schema").asString())
          .as(file.toString())
          .isEqualTo("https://json-schema.org/draft/2020-12/schema");
      assertThat(node.path("$id").asString())
          .as(file.toString())
          .isEqualTo(BASE + file.getFileName());
      assertThat(meta.validate(text, InputFormat.JSON)).as(file.toString()).isEmpty();
    }
  }

  @Test
  void everyReferenceBetweenSchemasResolves() throws IOException {
    for (Path file : schemaFiles()) {
      for (String ref : refs(mapper.readTree(Files.readString(file, StandardCharsets.UTF_8)))) {
        if (ref.startsWith("#")) {
          continue;
        }
        assertResolves(file.toString(), BASE + ref);
      }
    }
  }

  @Test
  void everyFixtureValidatesAsItsFolderSays() throws IOException {
    int checked = 0;
    try (Stream<Path> targets = Files.list(EXAMPLES)) {
      for (Path target : targets.filter(Files::isDirectory).sorted().toList()) {
        Schema schema = registry.getSchema(SchemaLocation.of(locationOf(target)));
        for (Path fixture : jsonFiles(target.resolve("valid"))) {
          assertThat(schema.validate(read(fixture), InputFormat.JSON))
              .as("valid fixture " + fixture)
              .isEmpty();
          checked++;
        }
        for (Path fixture : jsonFiles(target.resolve("invalid"))) {
          assertThat(schema.validate(read(fixture), InputFormat.JSON))
              .as("invalid fixture " + fixture)
              .isNotEmpty();
          checked++;
        }
      }
    }
    assertThat(checked).isGreaterThanOrEqualTo(90);
  }

  @Test
  void theOpenApiDocumentServesExactlyTheSpecifiedRoutes() throws IOException {
    JsonNode doc = mapper.readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8));
    assertThat(doc.path("openapi").asString()).startsWith("3.1.");
    assertThat(new TreeSet<>(doc.path("paths").propertyNames()))
        .containsExactlyInAnyOrderElementsOf(ROUTES);
    for (String route : doc.path("paths").propertyNames()) {
      assertThat(List.of("get", "post"))
          .as("only GET and POST pass the ingest bot filter: " + route)
          .containsAll(doc.path("paths").path(route).propertyNames());
    }
  }

  @Test
  void everySchemaTheOpenApiDocumentNamesExistsAndHasFixturesBothWays() throws IOException {
    JsonNode doc = mapper.readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8));
    Set<String> targets = new TreeSet<>();
    for (String ref : refs(doc)) {
      if (!ref.startsWith(BASE)) {
        continue;
      }
      assertResolves("openapi", ref);
      targets.add(fixtureFolderOf(ref.substring(BASE.length())));
    }
    assertThat(targets).isNotEmpty();
    for (String target : targets) {
      assertThat(jsonFiles(EXAMPLES.resolve(target).resolve("valid"))).as(target).isNotEmpty();
      assertThat(jsonFiles(EXAMPLES.resolve(target).resolve("invalid"))).as(target).isNotEmpty();
    }
  }

  @Test
  void theErrorRegistryHoldsUniqueCodesWithErrorStatuses() throws IOException {
    String text = Files.readString(ERRORS, StandardCharsets.UTF_8);
    String requestErrors = text.substring(0, text.indexOf("## Per-op reasons"));
    Matcher matcher = ERROR_ROW.matcher(requestErrors);
    Set<String> codes = new TreeSet<>();
    while (matcher.find()) {
      assertThat(codes.add(matcher.group(1))).as("duplicate " + matcher.group(1)).isTrue();
      assertThat(matcher.group(2)).as(matcher.group(1)).matches("[45]\\d\\d");
    }
    assertThat(codes)
        .contains(
            "DPOP_REQUIRED",
            "INSTALLATION_REVOKED",
            "CLIENT_VERSION_UNSUPPORTED",
            "MASS_CHANGE_CONFIRMATION_REQUIRED",
            "EXCHANGE_BUDGET_EXHAUSTED",
            "LEGACY_ENDPOINT_GONE")
        .hasSizeGreaterThanOrEqualTo(30);
  }

  @Test
  void theSchemasOnlyGrewSinceThePreviousRelease() throws IOException {
    String baselineDir = System.getProperty("exchange.baseline", "");
    Path baseline = Path.of(baselineDir);
    Assumptions.assumeTrue(
        !baselineDir.isBlank() && Files.isDirectory(baseline) && !jsonFiles(baseline).isEmpty(),
        "no -Dexchange.baseline pointing at the previous release's v1 schemas; the release carries"
            + " none yet, so there is nothing to stay compatible with");
    List<String> breaking = new ArrayList<>();
    for (Path released : jsonFiles(baseline)) {
      Path current = SCHEMAS.resolve(released.getFileName().toString());
      if (!Files.exists(current)) {
        breaking.add(released.getFileName() + ": removed");
        continue;
      }
      breaking.addAll(
          SchemaCompatibility.breakingChanges(
              mapper.readTree(read(released)),
              mapper.readTree(read(current)),
              released.getFileName() + "#"));
    }
    assertThat(breaking).as("breaking changes within v1 (REQ-XCH-026)").isEmpty();
  }

  /**
   * Asserts that a schema reference loads and, when it carries a JSON Pointer fragment, that the
   * pointer names a node of the referenced document.
   *
   * @param context what holds the reference, for the failure message
   * @param absoluteRef the reference resolved against {@link #BASE}
   * @throws IOException when the referenced file cannot be read
   */
  private void assertResolves(@NotNull String context, @NotNull String absoluteRef)
      throws IOException {
    int hash = absoluteRef.indexOf('#');
    String file = hash < 0 ? absoluteRef : absoluteRef.substring(0, hash);
    Path local = SCHEMAS.resolve(file.substring(BASE.length()));
    assertThat(local).as(context + " -> " + absoluteRef).exists();
    if (hash >= 0 && hash < absoluteRef.length() - 1) {
      JsonNode target =
          mapper
              .readTree(Files.readString(local, StandardCharsets.UTF_8))
              .at(absoluteRef.substring(hash + 1));
      assertThat(target.isMissingNode()).as(context + " -> " + absoluteRef).isFalse();
    }
  }

  /**
   * Maps a fixture folder to the schema location it targets: {@code name} to the whole schema,
   * {@code name--def} to {@code name.schema.json#/$defs/def}.
   *
   * @param folder a folder under the fixture root
   * @return the absolute schema location
   */
  private static @NotNull String locationOf(@NotNull Path folder) {
    String name = folder.getFileName().toString();
    int split = name.indexOf("--");
    return split < 0
        ? BASE + name + ".schema.json"
        : BASE + name.substring(0, split) + ".schema.json#/$defs/" + name.substring(split + 2);
  }

  /**
   * Maps a schema reference relative to {@link #BASE} to the fixture folder that must cover it.
   *
   * @param relative a reference such as {@code page.schema.json#/$defs/stockPage}
   * @return the folder name, such as {@code page--stockPage}
   */
  private static @NotNull String fixtureFolderOf(@NotNull String relative) {
    int hash = relative.indexOf('#');
    String file = (hash < 0 ? relative : relative.substring(0, hash)).replace(".schema.json", "");
    return hash < 0 ? file : file + "--" + relative.substring(relative.lastIndexOf('/') + 1);
  }

  /**
   * Collects every {@code $ref} value in a JSON tree, depth first.
   *
   * @param node the tree to walk
   * @return the reference strings in document order
   */
  private static @NotNull List<String> refs(@NotNull JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node.isObject()) {
      for (String field : node.propertyNames()) {
        JsonNode child = node.get(field);
        if ("$ref".equals(field) && child.isString()) {
          out.add(child.asString());
        } else {
          out.addAll(refs(child));
        }
      }
    } else if (node.isArray()) {
      node.forEach(child -> out.addAll(refs(child)));
    }
    return out;
  }

  /**
   * Lists the published schema files in a stable order.
   *
   * @return every {@code *.schema.json} under the schema source folder
   * @throws IOException when the folder cannot be listed
   */
  private static @NotNull List<Path> schemaFiles() throws IOException {
    try (Stream<Path> files = Files.list(SCHEMAS)) {
      return files.filter(p -> p.toString().endsWith(".schema.json")).sorted().toList();
    }
  }

  /**
   * Lists the JSON fixtures in one folder, or none when the folder does not exist.
   *
   * @param folder a {@code valid} or {@code invalid} fixture folder
   * @return the fixture files in a stable order
   * @throws IOException when an existing folder cannot be listed
   */
  private static @NotNull List<Path> jsonFiles(@NotNull Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(folder)) {
      return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    }
  }

  /**
   * Reads a fixture as UTF-8 text.
   *
   * @param file the fixture
   * @return its content
   * @throws IOException when it cannot be read
   */
  private static @NotNull String read(@NotNull Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
