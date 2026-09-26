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

package de.greluc.krt.profit.basetool.frontend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * ArchUnit tests enforcing that the frontend never accesses PostgreSQL or the Keycloak Admin API
 * directly, and that every controller carries its own gate (REQ-SEC-052).
 */
class ArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.frontend");

  @Test
  void frontendShouldNotDependOnSpringDataJpa() {
    noClasses()
        .that()
        .resideInAPackage("de.greluc.krt.profit.basetool.frontend..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.data.jpa..")
        .because(
            "The frontend module is forbidden from talking to the database directly; "
                + "data access goes through BackendApiClient.")
        .check(CLASSES);
  }

  @Test
  void frontendShouldNotUseJdbcDirectly() {
    noClasses()
        .that()
        .resideInAPackage("de.greluc.krt.profit.basetool.frontend..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.sql.Connection")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.sql.Statement")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.sql.PreparedStatement")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.sql.DriverManager")
        .because(
            "The frontend module has no business holding a JDBC connection; "
                + "all persistence goes through the backend module via BackendApiClient.")
        .check(CLASSES);
  }

  /**
   * Verifies that every controller carries at least one {@code @PreAuthorize}, on the class or a
   * handler, unless it is public by design (REQ-SEC-052).
   */
  @Test
  void everyControllerCarriesAGateOfItsOwn() {
    List<String> ungated =
        controllers().stream()
            .filter(c -> !PUBLIC_BY_DESIGN.contains(c.getSimpleName()))
            .filter(ArchitectureTest::hasNoAuthorizationAnnotation)
            .map(JavaClass::getSimpleName)
            .sorted()
            .toList();

    assertThat(ungated)
        .as(
            "every controller must carry a @PreAuthorize of its own (class or handler) unless it is"
                + " one of the %d public-by-design pages; these carry none",
            PUBLIC_BY_DESIGN.size())
        .isEmpty();
  }

  /**
   * The allow-list above names classes that must keep existing, so a rename cannot quietly widen it
   * into a list of controllers nobody checks any more.
   */
  @Test
  void thePublicByDesignAllowListNamesOnlyControllersThatExist() {
    Set<String> present =
        controllers().stream()
            .map(JavaClass::getSimpleName)
            .collect(java.util.stream.Collectors.toSet());

    assertThat(present).containsAll(PUBLIC_BY_DESIGN);
  }

  /**
   * Verifies that every view controller opts into the layout model via {@code @UsesLayoutModel}.
   */
  @Test
  void everyViewControllerOptsIntoTheLayoutModel() {
    List<String> unmarked =
        CLASSES.stream()
            .filter(c -> c.isAnnotatedWith(Controller.class))
            .filter(c -> !c.isAnnotatedWith(UsesLayoutModel.class))
            .map(JavaClass::getSimpleName)
            .sorted()
            .toList();

    assertThat(unmarked)
        .as("every @Controller must opt into the layout model with @UsesLayoutModel")
        .isEmpty();
  }

  @Test
  void noRestControllerOptsIntoTheLayoutModel() {
    List<String> marked =
        CLASSES.stream()
            .filter(c -> c.isAnnotatedWith(RestController.class))
            .filter(c -> c.isAnnotatedWith(UsesLayoutModel.class))
            .map(JavaClass::getSimpleName)
            .sorted()
            .toList();

    assertThat(marked)
        .as("a @RestController discards the layout model; it must not carry @UsesLayoutModel")
        .isEmpty();
  }

  /**
   * Ratchet ensuring the number of body-writing handlers inside {@code @UsesLayoutModel}
   * controllers never exceeds {@link #BODY_HANDLERS_IN_LAYOUT_CONTROLLERS} (FE-PERF-01). New JSON
   * endpoints belong in a {@code @RestController}.
   */
  @Test
  void bodyWritingHandlersInLayoutControllersOnlyEverDecrease() {
    long count =
        CLASSES.stream()
            .filter(c -> c.isAnnotatedWith(UsesLayoutModel.class))
            .flatMap(c -> c.getMethods().stream())
            .filter(m -> m.isMetaAnnotatedWith(RequestMapping.class))
            .filter(ArchitectureTest::writesItsOwnBody)
            .count();

    assertThat(count)
        .as(
            "a new JSON handler belongs in a @RestController, not in a @UsesLayoutModel view"
                + " controller (FE-PERF-01)")
        .isLessThanOrEqualTo(BODY_HANDLERS_IN_LAYOUT_CONTROLLERS);
    assertThat(count)
        .as(
            "handlers were moved out — lower BODY_HANDLERS_IN_LAYOUT_CONTROLLERS to %d so the"
                + " ratchet holds the gain",
            count)
        .isEqualTo(BODY_HANDLERS_IN_LAYOUT_CONTROLLERS);
  }

  /**
   * The maximum number of body-writing handlers inside {@code @UsesLayoutModel} controllers; lower
   * it when handlers move out, never raise it.
   */
  private static final long BODY_HANDLERS_IN_LAYOUT_CONTROLLERS = 215L;

  /**
   * Whether a handler method writes its response body directly, mirroring the shapes {@code
   * LayoutContextLoader#decide} recognises.
   *
   * @param method the handler method
   * @return {@code true} for a {@code ResponseBody} method or an {@code HttpEntity}, {@code
   *     ResponseBodyEmitter} or {@code StreamingResponseBody} return type
   */
  private static boolean writesItsOwnBody(JavaMethod method) {
    return method.isAnnotatedWith(ResponseBody.class)
        || method.getRawReturnType().isAssignableTo(HttpEntity.class)
        || method.getRawReturnType().isAssignableTo(ResponseBodyEmitter.class)
        || method.getRawReturnType().isAssignableTo(StreamingResponseBody.class);
  }

  /**
   * The controllers that carry no {@code @PreAuthorize} because they must answer without a session,
   * such as {@code AppLinkController}, the Android App Link's web-side fallback (REQ-SEC-038).
   */
  private static final Set<String> PUBLIC_BY_DESIGN =
      Set.of(
          "AppLinkController",
          "AssetLinksController",
          "HomeController",
          "ImpressumController",
          "OssLicensesController",
          "PrivacyController",
          "TermsController",
          "WebAppManifestController");

  /**
   * Every Spring MVC controller of the frontend module.
   *
   * @return the {@code @Controller} and {@code @RestController} classes; {@code @RestController} is
   *     checked explicitly because ArchUnit reads direct annotations and does not follow the
   *     meta-annotation to {@code @Controller}
   */
  private static List<JavaClass> controllers() {
    return CLASSES.stream()
        .filter(c -> c.isAnnotatedWith(Controller.class) || c.isAnnotatedWith(RestController.class))
        .toList();
  }

  /**
   * Whether a controller carries no authorization annotation at all.
   *
   * @param controller the controller class
   * @return {@code true} when neither the class nor any of its methods is annotated
   *     {@code @PreAuthorize}
   */
  private static boolean hasNoAuthorizationAnnotation(JavaClass controller) {
    return !controller.isAnnotatedWith(PreAuthorize.class)
        && controller.getMethods().stream().noneMatch(m -> m.isAnnotatedWith(PreAuthorize.class));
  }
}
