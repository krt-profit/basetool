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
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * ArchUnit tests that enforce CLAUDE.md's "frontend never talks to PostgreSQL or Keycloak Admin API
 * directly" rule mechanically.
 *
 * <p>The frontend is supposed to be a thin Thymeleaf renderer that delegates every data access to
 * the backend via {@code BackendApiClient}; any drift towards "let me just open a JDBC connection
 * for this one widget" is exactly the kind of subtle architecture rot that's hard to spot in PR
 * review but trivial for a static check. The third rule is REQ-SEC-052's half of the same idea: a
 * controller whose only protection lives in a URL matcher two folders away.
 */
class ArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.frontend");

  @Test
  void frontendShouldNotDependOnSpringDataJpa() {
    // No code under `de.greluc.krt.profit.basetool.frontend.*` may reference any class
    // from `org.springframework.data.jpa..` — that includes JpaRepository,
    // EntityManager helpers, etc. The frontend module deliberately does not pull
    // the spring-boot-starter-data-jpa dependency, so this check is a belt-and-
    // suspenders guard against accidentally adding it on a hot fix.
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
    // Sibling rule to JpaRepository: the frontend must not open JDBC connections of
    // its own either. `java.sql.Connection`/`Statement`/`PreparedStatement` are
    // forbidden imports.
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
   * REQ-SEC-052: no controller is protected <em>only</em> by a URL matcher two folders away.
   *
   * <p>The members-only change moved thirteen handlers out from under a {@code permitAll} rule and
   * gave their classes a {@code @PreAuthorize("isAuthenticated()")} floor. {@code
   * ProfileController} was missed — its three {@code isAnonymous()} guards went with the anonymous
   * caller and nothing took their place, leaving one class in the set covered by the catch-all
   * alone. Nothing failed, which is the point: a gap in defence in depth is invisible until the
   * layer above it regresses.
   *
   * <p>The bar here is deliberately the floor and not per-handler coverage: a controller must carry
   * at least one {@code @PreAuthorize}, on the class or on a handler. Demanding one on every method
   * would fail on the thirty-odd controllers that gate the handlers that need a role and let the
   * class floor cover the rest, which is a legitimate shape. What this catches is a controller with
   * no gate anywhere — exactly the shape that was found.
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
   * The five controllers that answer without a session, one public path each: the landing page, the
   * three legal pages, and the Android App Links descriptor the platform fetches with no session at
   * all (REQ-SEC-038). They are exactly the frontend {@code permitAll} entries that are served by a
   * controller rather than by the static-asset handlers, and REQ-SEC-052 enumerates them.
   */
  private static final Set<String> PUBLIC_BY_DESIGN =
      Set.of(
          "AssetLinksController",
          "HomeController",
          "ImpressumController",
          "PrivacyController",
          "TermsController");

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
