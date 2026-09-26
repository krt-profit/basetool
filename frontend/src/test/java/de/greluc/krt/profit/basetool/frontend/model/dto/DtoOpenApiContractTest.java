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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract test pinning the frontend DTO mirror records against the backend's committed OpenAPI
 * document ({@code backend/src/main/resources/api/openapi.json}).
 *
 * <p>For every record in {@code model.dto} with a same-named schema, each component must exist as a
 * schema property; new API fields are allowed. Records without a schema and schemas without {@code
 * properties} are skipped, and a floor on matched records guards against checking nothing.
 */
class DtoOpenApiContractTest {

  /** Package holding the frontend's hand-mirrored DTO records. */
  private static final String DTO_PACKAGE = "de.greluc.krt.profit.basetool.frontend.model.dto";

  /**
   * Minimum number of records that must match a schema-with-properties. Far below the real count
   * (dozens of 1:1 mirrors); a value this low only trips when class scanning or spec loading breaks
   * entirely, which would otherwise make the whole test pass vacuously.
   */
  private static final int MIN_MATCHED_RECORDS = 40;

  /**
   * Minimum number of mirror enums that must match a schema carrying an {@code enum} array. A floor
   * of one keeps the enum-coverage check from passing vacuously if class scanning or spec loading
   * breaks (today {@code OrgUnitKind} alone satisfies it).
   */
  private static final int MIN_MATCHED_ENUMS = 1;

  /**
   * Frontend enums in the DTO package that mirror another module than the backend, so no backend
   * schema is theirs: {@code HandoffKind} mirrors the ingest gateway's staged-handoff kind. Without
   * this list the superset fallback below pairs such an enum with whichever backend enum happens to
   * contain its values.
   */
  private static final Set<String> NOT_BACKEND_MIRRORS = Set.of("HandoffKind");

  /**
   * Allowlist of known mirror drifts (frontend components the schema does not declare); currently
   * empty, and it must never grow.
   */
  private static final Map<String, Set<String>> KNOWN_PREEXISTING_DRIFT = Map.of();

  /**
   * Builds one dynamic test per matched frontend record plus a final guard asserting enough records
   * were matched, so a failure names the exact record and missing field.
   *
   * @return the per-record contract checks followed by the match-count guard
   * @throws IOException never (wrapped); the spec is loaded eagerly and any failure is reported as
   *     a test failure rather than an error
   */
  @TestFactory
  List<DynamicTest> frontendDtoComponentsExistInOpenApiSchema() throws IOException {
    JsonNode schemas = loadSchemas();

    List<DynamicTest> tests = new ArrayList<>();
    int matched = 0;

    for (Class<?> record : frontendDtoRecords()) {
      JsonNode schema = schemas.path(record.getSimpleName());
      JsonNode properties = schema.path("properties");
      if (schema.isMissingNode() || properties.isMissingNode() || !properties.isObject()) {
        continue;
      }
      matched++;
      tests.add(
          DynamicTest.dynamicTest(
              record.getSimpleName() + " components exist in OpenAPI schema",
              () -> assertRecordMatchesSchema(record, properties)));
    }

    int matchedFinal = matched;
    tests.add(
        DynamicTest.dynamicTest(
            "matched at least " + MIN_MATCHED_RECORDS + " DTO records against the spec",
            () ->
                assertTrue(
                    matchedFinal >= MIN_MATCHED_RECORDS,
                    "Only "
                        + matchedFinal
                        + " frontend DTO records matched an OpenAPI schema (expected >= "
                        + MIN_MATCHED_RECORDS
                        + "). Class scanning or openapi.json loading is likely broken, which would"
                        + " make this contract test pass without checking anything.")));
    return tests;
  }

  /**
   * Builds one dynamic test per matched frontend mirror enum, asserting it declares every constant
   * the backend OpenAPI schema can emit, since an unknown constant fails deserialization of the
   * whole response. Extra mirror-only constants are ignored.
   *
   * @return the per-enum coverage checks followed by the match-count guard
   * @throws IOException never (wrapped); see {@link #loadSchemas()}
   */
  @TestFactory
  List<DynamicTest> frontendEnumConstantsCoverOpenApiEnum() throws IOException {
    List<Set<String>> backendEnums = collectStringEnums(loadRoot());

    List<DynamicTest> tests = new ArrayList<>();
    int matched = 0;

    for (Class<?> type : frontendDtoEnums()) {
      if (NOT_BACKEND_MIRRORS.contains(type.getSimpleName())) {
        continue;
      }
      Set<String> mirror = enumConstantNames(type);
      Set<String> backend =
          backendEnums.stream()
              .filter(mirror::equals)
              .findFirst()
              .orElseGet(
                  () ->
                      backendEnums.stream()
                          .filter(candidate -> candidate.containsAll(mirror))
                          .max(Comparator.comparingInt(Set::size))
                          .orElse(null));
      if (backend == null) {
        continue;
      }
      matched++;
      tests.add(
          DynamicTest.dynamicTest(
              type.getSimpleName() + " mirror covers every OpenAPI enum value",
              () -> assertEnumCoversSchema(type, mirror, backend)));
    }

    int matchedFinal = matched;
    tests.add(
        DynamicTest.dynamicTest(
            "matched at least " + MIN_MATCHED_ENUMS + " DTO enum(s) against the spec",
            () ->
                assertTrue(
                    matchedFinal >= MIN_MATCHED_ENUMS,
                    "Only "
                        + matchedFinal
                        + " frontend DTO enum(s) matched an OpenAPI enum schema (expected >= "
                        + MIN_MATCHED_ENUMS
                        + "). Class scanning or openapi.json loading is likely broken, which would"
                        + " make this enum-coverage check pass without checking anything.")));
    return tests;
  }

  /**
   * Asserts every component of {@code record} appears as a property of {@code schemaProperties},
   * collecting all misses into a single message rather than failing on the first.
   *
   * @param record the frontend DTO record under test
   * @param schemaProperties the {@code properties} node of the matching OpenAPI schema
   */
  private static void assertRecordMatchesSchema(Class<?> record, JsonNode schemaProperties) {
    Set<String> allowed = KNOWN_PREEXISTING_DRIFT.getOrDefault(record.getSimpleName(), Set.of());
    Set<String> missing = new TreeSet<>();
    for (RecordComponent component : record.getRecordComponents()) {
      String jsonName = jsonName(record, component);
      if (!schemaProperties.has(jsonName) && !allowed.contains(jsonName)) {
        missing.add(jsonName);
      }
    }
    assertTrue(
        missing.isEmpty(),
        () ->
            "Frontend record "
                + record.getSimpleName()
                + " has field(s) "
                + missing
                + " that the backend OpenAPI schema no longer declares. Either the backend DTO"
                + " changed (update the frontend mirror + its template), or a @JsonProperty name"
                + " drifted.");
  }

  /**
   * Asserts the frontend mirror enum {@code type} declares a constant for every value the matched
   * backend enum can emit, collecting all misses into a single message.
   *
   * @param type the frontend mirror enum under test
   * @param mirror the mirror's own constant names
   * @param backend the value set of the backend inline enum the mirror reflects
   */
  private static void assertEnumCoversSchema(
      Class<?> type, Set<String> mirror, Set<String> backend) {
    Set<String> missing = new TreeSet<>(backend);
    missing.removeAll(mirror);
    assertTrue(
        missing.isEmpty(),
        () ->
            "Frontend mirror enum "
                + type.getSimpleName()
                + " is missing constant(s) "
                + missing
                + " that the backend OpenAPI schema can emit. A response carrying such a value"
                + " fails JSON deserialisation outright (the whole payload 500s) — add the missing"
                + " constant(s) to the frontend mirror.");
  }

  /**
   * Collects the value set of every constant of a frontend mirror enum.
   *
   * @param type the enum class
   * @return its constant names
   */
  private static Set<String> enumConstantNames(Class<?> type) {
    Set<String> names = new TreeSet<>();
    for (Object constant : type.getEnumConstants()) {
      names.add(((Enum<?>) constant).name());
    }
    return names;
  }

  /**
   * Collects the value set of every inline string {@code enum} in the OpenAPI document, since
   * SpringDoc inlines enums instead of emitting shared component schemas.
   *
   * @param root the OpenAPI document root
   * @return one set per inline string enum found, duplicates retained
   */
  private static List<Set<String>> collectStringEnums(JsonNode root) {
    List<Set<String>> sink = new ArrayList<>();
    collectStringEnums(root, sink);
    return sink;
  }

  /**
   * Recursive worker for {@link #collectStringEnums(JsonNode)}: records this node's {@code enum}
   * array (when it is a non-empty string array) then recurses into every child value.
   *
   * @param node the current node
   * @param sink the accumulator of discovered string-enum value sets
   */
  private static void collectStringEnums(JsonNode node, List<Set<String>> sink) {
    JsonNode enumNode = node.path("enum");
    if (enumNode.isArray()) {
      Set<String> values = new TreeSet<>();
      for (JsonNode value : enumNode) {
        if (value.isString()) {
          values.add(value.asString());
        }
      }
      if (!values.isEmpty()) {
        sink.add(values);
      }
    }
    node.forEach(child -> collectStringEnums(child, sink));
  }

  /**
   * Resolves a record component's JSON property name: the non-empty {@code @JsonProperty} value on
   * its field, matched by annotation simple name so Jackson 2 and 3 both work, else the component
   * name.
   *
   * @param record the declaring record class
   * @param component the record component
   * @return the effective JSON property name
   */
  private static String jsonName(Class<?> record, RecordComponent component) {
    try {
      Field field = record.getDeclaredField(component.getName());
      for (Annotation annotation : field.getAnnotations()) {
        if ("JsonProperty".equals(annotation.annotationType().getSimpleName())) {
          Object value = annotation.annotationType().getMethod("value").invoke(annotation);
          if (value instanceof String name && !name.isEmpty()) {
            return name;
          }
        }
      }
    } catch (ReflectiveOperationException ignored) {
    }
    return component.getName();
  }

  /**
   * Scans {@link #DTO_PACKAGE} for record classes via a Spring classpath scan (accept-all filter,
   * default filters off), so the test discovers new DTOs automatically instead of being
   * hand-listed.
   *
   * @return the frontend DTO record classes
   */
  private static List<Class<?>> frontendDtoRecords() {
    return frontendDtoTypes(Class::isRecord);
  }

  /**
   * Scans {@link #DTO_PACKAGE} for enum classes, so the enum-coverage check discovers new mirror
   * enums automatically instead of being hand-listed.
   *
   * @return the frontend DTO enum classes
   */
  private static List<Class<?>> frontendDtoEnums() {
    return frontendDtoTypes(Class::isEnum);
  }

  /**
   * Scans {@link #DTO_PACKAGE} via a Spring classpath scan (accept-all filter, default filters off)
   * and returns the loaded classes the given filter accepts.
   *
   * @param filter the predicate selecting which scanned classes to keep (records, enums, …)
   * @return the matching frontend DTO classes
   */
  private static List<Class<?>> frontendDtoTypes(Predicate<Class<?>> filter) {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
    List<Class<?>> types = new ArrayList<>();
    scanner
        .findCandidateComponents(DTO_PACKAGE)
        .forEach(
            beanDefinition -> {
              try {
                Class<?> type = Class.forName(beanDefinition.getBeanClassName());
                if (filter.test(type)) {
                  types.add(type);
                }
              } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "Scanned DTO class not loadable: " + beanDefinition.getBeanClassName(), e);
              }
            });
    return types;
  }

  /**
   * Loads {@code components.schemas} from the backend's committed OpenAPI document, found by
   * walking up from the working directory.
   *
   * @return the {@code components.schemas} node
   * @throws IOException if the spec cannot be read
   */
  private static JsonNode loadSchemas() throws IOException {
    JsonNode schemas = loadRoot().path("components").path("schemas");
    assertFalse(
        schemas.isMissingNode() || !schemas.isObject(),
        "components.schemas missing in " + locateOpenApiSpec().toAbsolutePath());
    return schemas;
  }

  /**
   * Reads and parses the committed OpenAPI document into its root node.
   *
   * @return the parsed OpenAPI document root
   * @throws IOException if the spec cannot be read
   */
  private static JsonNode loadRoot() throws IOException {
    return JsonMapper.builder().build().readTree(Files.readString(locateOpenApiSpec()));
  }

  /**
   * Finds {@code backend/src/main/resources/api/openapi.json} by walking up from the working
   * directory. Both the module dir ({@code frontend/}) and the repo root resolve it.
   *
   * @return the path to the committed OpenAPI document
   */
  private static Path locateOpenApiSpec() {
    Path relative = Paths.get("backend", "src", "main", "resources", "api", "openapi.json");
    for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(relative);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new UncheckedIOException(
        new IOException(
            "Could not locate backend/src/main/resources/api/openapi.json by walking up from "
                + Paths.get("").toAbsolutePath()));
  }
}
