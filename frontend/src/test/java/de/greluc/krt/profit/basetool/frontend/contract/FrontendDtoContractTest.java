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

package de.greluc.krt.profit.basetool.frontend.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintCatalogueMetadata;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * Contract test diffing every hand-mirrored frontend {@link DtoMirror} record against the
 * same-named schema in the committed backend {@code openapi.json}, without classpath coupling.
 *
 * <p>It fails on:
 *
 * <ul>
 *   <li>a record component with no matching schema property;
 *   <li>an enum property mirrored as {@code String} without {@link BackendEnumAsString};
 *   <li>a typed-enum mirror lacking a backend enum value;
 *   <li>a {@link BackendEnumAsString} on a property the schema no longer types as an enum.
 * </ul>
 *
 * <p>Frontend-only records are skipped, and their count is asserted to stay a small minority.
 */
class FrontendDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Candidate locations of the committed OpenAPI document, relative to the test working dir. */
  private static final List<Path> OPENAPI_CANDIDATES =
      List.of(
          Path.of("..", "backend", "src", "main", "resources", "api", "openapi.json"),
          Path.of("backend", "src", "main", "resources", "api", "openapi.json"));

  @Test
  void frontendDtosMatchTheOpenApiContract() throws IOException {
    JsonNode schemas = loadOpenApiSchemas();
    List<Class<?>> records = frontendDtoRecords();
    assertThat(records)
        .as("the @DtoMirror scan should find the hand-mirrored DTO records")
        .hasSizeGreaterThan(100);

    List<String> problems = new ArrayList<>();
    int contractChecked = 0;
    int frontendOnly = 0;

    for (Class<?> record : records) {
      JsonNode schema = schemas.get(record.getSimpleName());
      if (schema == null || !schema.hasNonNull("properties")) {
        frontendOnly++;
        continue;
      }
      contractChecked++;
      JsonNode properties = schema.get("properties");
      for (RecordComponent component : record.getRecordComponents()) {
        checkComponent(record, component, properties, problems);
      }
    }

    assertThat(contractChecked)
        .as("a meaningful share of frontend DTOs should map onto an openapi.json schema")
        .isGreaterThan(100);
    assertThat(frontendOnly)
        .as(
            "frontend-only DTOs (no matching schema) should stay a minority of the %d records",
            records.size())
        .isLessThan(records.size() / 2);

    assertThat(problems)
        .as(
            "Frontend DTOs drifted from the backend openapi.json contract. Fix the frontend mirror,"
                + " add @BackendEnumAsString where a String demotion is intended, or regenerate"
                + " openapi.json if the backend legitimately changed:%n%s",
            String.join(System.lineSeparator(), problems))
        .isEmpty();
  }

  /**
   * Runs the four contract checks (shape, un-annotated demotion, typed-enum value drift, stale
   * annotation) for a single record component against its schema property, appending a
   * human-readable line to {@code problems} for every violation.
   *
   * @param record the frontend DTO record being checked
   * @param component the record component (field) under inspection
   * @param properties the {@code properties} node of the matching openapi schema
   * @param problems accumulator for violation messages
   */
  private void checkComponent(
      Class<?> record, RecordComponent component, JsonNode properties, List<String> problems) {
    String qualified = record.getSimpleName() + "." + component.getName();
    JsonNode property = properties.get(component.getName());
    boolean annotated = component.getAnnotation(BackendEnumAsString.class) != null;

    if (property == null) {
      problems.add(
          qualified
              + " — no matching property in the openapi schema (backend renamed/removed it?).");
      return;
    }

    boolean schemaEnum = property.hasNonNull("enum");
    Class<?> type = component.getType();

    if (annotated && !schemaEnum) {
      problems.add(
          qualified
              + " — carries @BackendEnumAsString but openapi no longer types it as an enum; drop"
              + " the annotation.");
    }

    if (!schemaEnum) {
      return;
    }

    if (type == String.class) {
      if (!annotated) {
        problems.add(
            qualified
                + " — openapi types this as an enum "
                + enumValues(property)
                + " but the frontend mirrors it as String without @BackendEnumAsString. Add the"
                + " annotation to choose the demotion, or use the typed enum.");
      }
    } else if (type.isEnum()) {
      Set<String> frontendValues = new LinkedHashSet<>();
      for (Object constant : type.getEnumConstants()) {
        frontendValues.add(MAPPER.valueToTree(constant).asText());
      }
      Set<String> missing = new TreeSet<>(enumValueSet(property));
      missing.removeAll(frontendValues);
      if (!missing.isEmpty()) {
        problems.add(
            qualified
                + " — backend enum has value(s) "
                + missing
                + " that the frontend enum "
                + type.getSimpleName()
                + " lacks; deserialization of those would fail.");
      }
    } else {
      problems.add(
          qualified
              + " — openapi types this as an enum but the frontend component is "
              + type.getSimpleName()
              + " (neither the typed enum nor an annotated String).");
    }
  }

  /**
   * Enumerates every {@code record} among the {@link DtoMirror} types, nested ones included.
   *
   * @return the frontend DTO record classes
   */
  private List<Class<?>> frontendDtoRecords() {
    return DtoMirrorScan.types().stream().filter(Class::isRecord).toList();
  }

  /**
   * Loads {@code components.schemas} from the committed OpenAPI document, trying each candidate
   * path so the test works whether Gradle runs it from the module dir or the repo root.
   *
   * @return the {@code components.schemas} node keyed by backend DTO simple name
   * @throws IOException when the document cannot be read
   */
  private JsonNode loadOpenApiSchemas() throws IOException {
    Path path =
        OPENAPI_CANDIDATES.stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() ->
                new IllegalStateException(
                    "openapi.json not found; looked at " + OPENAPI_CANDIDATES));
    JsonNode schemas = MAPPER.readTree(path.toFile()).path("components").path("schemas");
    assertThat(schemas.isObject())
        .as("components.schemas in %s should be an object", path)
        .isTrue();
    return schemas;
  }

  /**
   * Collects serialized enum values from an OpenAPI property's {@code enum} array.
   *
   * @param property the schema property node
   * @return the enum values as an insertion-ordered set
   */
  private Set<String> enumValueSet(JsonNode property) {
    Set<String> values = new LinkedHashSet<>();
    property.get("enum").forEach(node -> values.add(node.asText()));
    return values;
  }

  /**
   * Renders an openapi property's enum values for a violation message.
   *
   * @param property the schema property node
   * @return the enum values in {@code [A, B, C]} form
   */
  private String enumValues(JsonNode property) {
    return enumValueSet(property).toString();
  }

  @Test
  void annotatedEnumWireValuesMatchTheirSchemaInsteadOfTheirJavaNames() throws IOException {
    JsonNode properties =
        MAPPER.readTree(
            """
            {"kind":{"type":"string","enum":["group","item"]}}
            """);
    List<String> problems = new ArrayList<>();

    checkComponent(
        PropertyEnumMirror.class,
        PropertyEnumMirror.class.getRecordComponents()[0],
        properties,
        problems);
    checkComponent(
        ValueEnumMirror.class,
        ValueEnumMirror.class.getRecordComponents()[0],
        properties,
        problems);

    assertThat(problems).isEmpty();
    assertThat(MAPPER.writeValueAsString(PropertyKind.GROUP)).isEqualTo("\"group\"");
    assertThat(MAPPER.writeValueAsString(ValueKind.GROUP)).isEqualTo("\"group\"");
  }

  @Test
  void anAnnotatedEnumStillRejectsAnUnrepresentedBackendWireValue() throws IOException {
    JsonNode properties =
        MAPPER.readTree(
            """
            {"kind":{"type":"string","enum":["group","item","resource"]}}
            """);
    List<String> problems = new ArrayList<>();

    checkComponent(
        PropertyEnumMirror.class,
        PropertyEnumMirror.class.getRecordComponents()[0],
        properties,
        problems);

    assertThat(problems).hasSize(1);
    assertThat(problems.getFirst()).contains("[resource]").doesNotContain("[group", "[item");
  }

  @Test
  void blueprintRequirementKindsRoundTripTheirLowercaseWireValues() throws IOException {
    Set<String> wireValues = new LinkedHashSet<>();
    for (BlueprintCatalogueMetadata.RequirementKind kind :
        BlueprintCatalogueMetadata.RequirementKind.values()) {
      String wire = MAPPER.writeValueAsString(kind);
      assertThat(MAPPER.readValue(wire, BlueprintCatalogueMetadata.RequirementKind.class))
          .isEqualTo(kind);
      wireValues.add(MAPPER.readTree(wire).asText());
    }
    assertThat(wireValues).containsExactlyInAnyOrder("root", "group", "resource", "item");
  }

  /**
   * Models an enum whose field annotations define its JSON representation.
   *
   * @param kind annotated enum value
   */
  private record PropertyEnumMirror(PropertyKind kind) {}

  /**
   * Models an enum whose annotated value defines its JSON representation.
   *
   * @param kind value-backed enum
   */
  private record ValueEnumMirror(ValueKind kind) {}

  /** Source node kinds whose Java names differ from their annotated JSON names. */
  private enum PropertyKind {
    /** Group requirement represented by its lowercase source token. */
    @JsonProperty("group")
    GROUP,
    /** Item requirement represented by its lowercase source token. */
    @JsonProperty("item")
    ITEM
  }

  /** Source node kinds serialized through an explicit JSON value. */
  @RequiredArgsConstructor
  private enum ValueKind {
    /** Group requirement represented by its source token. */
    GROUP("group"),
    /** Item requirement represented by its source token. */
    ITEM("item");

    /** JSON token accepted by the backend contract. */
    @JsonValue private final String wireValue;
  }
}
