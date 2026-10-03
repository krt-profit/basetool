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

package de.greluc.krt.profit.basetool.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.config.ApiDomains;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps the OpenAPI domain tags of {@link ApiDomains} (REQ-API-018) in step with the domain map of
 * REQ-MOD-001: a controller's tag is its module, or the API domain a reviewed entry maps that
 * module or controller to.
 */
class ApiDomainsMatchTheDomainMapTest {

  /** The API domains that name a module differently, each with the reason. */
  static final Map<String, String> MODULE_TO_API =
      Map.of(
          "privacy",
          "identity: the REST cut serves the GDPR endpoints under identity; privacy is a"
              + " transitional code module",
          "admin",
          "admin-system: the REST cut's name for the admin module's system endpoints");

  /** Controllers whose API domain differs from their module's, each with the reason. */
  static final Map<String, String> CONTROLLER_TO_API =
      Map.of(
          "LeitungController",
          "leadership: the REST cut keeps the Leitung endpoints a domain; the code merges"
              + " leadership into orgunit",
          "BlueprintController",
          "blueprint: the REST cut serves the recipe graph with the blueprints; the code keeps"
              + " the synced graph in the catalogue",
          "UexLocationController",
          "catalogue: the REST cut serves the UEX locations with the catalogue; the code"
              + " serves the picker from the personal inventory");

  /** The controllers today; a smaller selection would check less. */
  private static final int CONTROLLER_FLOOR = 98;

  /**
   * Lists every disagreement between the tag and the module the map assigns.
   *
   * @param map the domain map
   * @param controllers the controller classes
   * @param tagOf the tag a controller carries
   * @param moduleToApi the reviewed module renames
   * @param controllerToApi the reviewed controller exceptions, keyed by simple name
   * @return one message per disagreement, and one per entry that no controller needs
   */
  static List<String> disagreements(
      @NotNull DomainMap map,
      @NotNull List<Class<?>> controllers,
      @NotNull java.util.function.Function<Class<?>, String> tagOf,
      @NotNull Map<String, String> moduleToApi,
      @NotNull Map<String, String> controllerToApi) {
    List<String> out = new ArrayList<>();
    Map<String, Boolean> used = new TreeMap<>();
    moduleToApi.keySet().forEach(k -> used.put("module " + k, false));
    controllerToApi.keySet().forEach(k -> used.put("controller " + k, false));
    for (Class<?> controller : controllers) {
      String module =
          map.ruleFor(controller.getName()).map(DomainMap.Rule::module).orElse("unassigned");
      String expected;
      if (controllerToApi.containsKey(controller.getSimpleName())) {
        expected = controllerToApi.get(controller.getSimpleName()).split(":", 2)[0];
        used.put("controller " + controller.getSimpleName(), true);
      } else if (moduleToApi.containsKey(module)) {
        expected = moduleToApi.get(module).split(":", 2)[0];
        used.put("module " + module, true);
      } else {
        expected = module;
      }
      String tag = tagOf.apply(controller);
      if (!expected.equals(tag)) {
        out.add(
            controller.getSimpleName()
                + " is tagged `"
                + tag
                + "` but its module `"
                + module
                + "` maps to `"
                + expected
                + "`");
      }
    }
    used.forEach(
        (entry, hit) -> {
          if (!hit) {
            out.add(entry + " is listed but no controller needs it");
          }
        });
    return out;
  }

  @Test
  void everyControllerIsTaggedWithItsModulesApiDomain() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("de.greluc.krt.profit.basetool.backend");
    List<Class<?>> controllers =
        classes.stream()
            .filter(c -> c.isAnnotatedWith(RestController.class))
            .map(JavaClass::reflect)
            .<Class<?>>map(c -> c)
            .toList();

    assertThat(controllers).hasSizeGreaterThanOrEqualTo(CONTROLLER_FLOOR);
    assertThat(
            disagreements(
                DomainMap.load(), controllers, ApiDomains::of, MODULE_TO_API, CONTROLLER_TO_API))
        .isEmpty();
  }

  @Test
  void aMistaggedControllerAndAStaleEntryAreReported() {
    DomainMap map = DomainMap.load();
    List<Class<?>> one =
        List.of(de.greluc.krt.profit.basetool.backend.controller.CityController.class);

    assertThat(disagreements(map, one, c -> "bank", Map.of(), Map.of()))
        .containsExactly(
            "CityController is tagged `bank` but its module `catalogue` maps to `catalogue`");
    assertThat(disagreements(map, one, c -> "catalogue", Map.of("gone", "x: reason"), Map.of()))
        .containsExactly("module gone is listed but no controller needs it");
  }
}
