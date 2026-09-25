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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that {@link AssertTrue} cross-field validators do not leak into the committed {@code
 * openapi.json} as schema properties.
 *
 * <p>Such guards look like boolean getters to springdoc, which publishes them as fake fields in
 * nondeterministic order. Each must carry {@code @Schema(hidden = true)}.
 */
class OpenApiDerivedPropertyTest {

  /** Production bytecode of the backend module; test classes are excluded deliberately. */
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend");

  /** The committed spec, relative to the module directory tests run in. */
  private static final Path OPENAPI = Paths.get("src/main/resources/api/openapi.json");

  /**
   * Every {@code @AssertTrue} validator on a type published under {@code components.schemas} must
   * be hidden from the OpenAPI document; a failure names the method and the annotation to add.
   *
   * @throws IOException if the committed spec cannot be read
   */
  @Test
  void everyAssertTrueValidatorOnAPublishedTypeIsHiddenFromTheOpenApiDocument() throws IOException {
    Set<String> publishedTypes = publishedSchemaNames();

    List<JavaMethod> validators =
        CLASSES.stream()
            .flatMap(javaClass -> javaClass.getMethods().stream())
            .filter(method -> method.isAnnotatedWith(AssertTrue.class))
            .filter(method -> publishedTypes.contains(method.getOwner().getSimpleName()))
            .sorted(java.util.Comparator.comparing(JavaMethod::getFullName))
            .toList();

    assertFalse(
        validators.isEmpty(),
        "found no @AssertTrue validators on published types at all — the classpath scan or the"
            + " schema lookup is broken, not the code");

    List<String> exposed = new ArrayList<>();
    for (JavaMethod validator : validators) {
      Schema schema = validator.reflect().getAnnotation(Schema.class);
      if (schema == null || !schema.hidden()) {
        exposed.add(validator.getFullName());
      }
    }

    assertTrue(
        exposed.isEmpty(),
        () ->
            "These @AssertTrue validators are derived from other fields and are not part of any"
                + " request payload, but they would be published as schema properties — and"
                + " accessor order is not stable across JVM runs, so they rewrite openapi.json"
                + " between builds. Annotate each with @Schema(hidden = true):\n  "
                + String.join("\n  ", exposed));
  }

  /**
   * Verifies that the committed spec itself publishes no {@code @AssertTrue} validator as a
   * property.
   *
   * @throws IOException if the committed spec cannot be read
   */
  @Test
  void theCommittedSpecPublishesNoAssertTrueValidator() throws IOException {
    Set<String> derived =
        CLASSES.stream()
            .flatMap(javaClass -> javaClass.getMethods().stream())
            .filter(method -> method.isAnnotatedWith(AssertTrue.class))
            .map(method -> propertyNameOf(method.getName()))
            .collect(Collectors.toCollection(TreeSet::new));

    List<String> offenders = new ArrayList<>();
    schemas()
        .forEach(
            (name, schema) -> {
              Object properties = ((Map<?, ?>) schema).get("properties");
              if (properties instanceof Map<?, ?> propertyMap) {
                propertyMap.keySet().stream()
                    .map(String::valueOf)
                    .filter(derived::contains)
                    .forEach(property -> offenders.add(name + "." + property));
              }
            });

    assertTrue(
        offenders.isEmpty(),
        () ->
            "openapi.json publishes derived @AssertTrue validators as request properties;"
                + " regenerate the spec after hiding them:\n  "
                + String.join("\n  ", offenders));
  }

  /**
   * Reads {@code components.schemas} from the committed spec.
   *
   * @return the schema map, keyed by schema name
   * @throws IOException if the committed spec cannot be read
   */
  private static Map<?, ?> schemas() throws IOException {
    Map<?, ?> document =
        JsonMapper.builder().build().readValue(Files.readString(OPENAPI), Map.class);
    return (Map<?, ?>) ((Map<?, ?>) document.get("components")).get("schemas");
  }

  /**
   * Names of every type published under {@code components.schemas}.
   *
   * @return the published schema names, which match the declaring types' simple names
   * @throws IOException if the committed spec cannot be read
   */
  private static Set<String> publishedSchemaNames() throws IOException {
    return schemas().keySet().stream().map(String::valueOf).collect(Collectors.toSet());
  }

  /**
   * Maps a boolean accessor name to the property name springdoc would publish, e.g. {@code
   * isSplitConfigConsistent} to {@code splitConfigConsistent}. Only the {@code is} prefix is
   * stripped.
   *
   * @param methodName the accessor's simple name
   * @return the published property name
   */
  private static String propertyNameOf(String methodName) {
    if (methodName.startsWith("is") && methodName.length() > 2) {
      String remainder = methodName.substring(2);
      return Character.toLowerCase(remainder.charAt(0)) + remainder.substring(1);
    }
    return methodName;
  }
}
