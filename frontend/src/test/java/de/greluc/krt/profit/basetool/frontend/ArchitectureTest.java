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
   * The controllers that answer without a session, one public path each: the landing page, the four
   * legal pages (the licence notice of REQ-UI-021 among them), the Android App Links descriptor the
   * platform fetches with no session at all (REQ-SEC-038), and the web app manifest a browser reads
   * on the landing page before anyone has signed in (REQ-UI-020, ADR-0164). They are exactly the
   * frontend {@code permitAll} entries that are served by a controller rather than by the
   * static-asset handlers, and REQ-SEC-052 enumerates them.
   *
   * <p>Both descriptors are on this list for the same reason rather than as an exception: a
   * {@code @PreAuthorize} on either would answer the platform with a redirect into OAuth, which is
   * precisely the failure they were written to prevent. Neither exposes data — the manifest carries
   * three localised strings, two colours and the path of an already-public icon.
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
   * Ratchet on the body-writing handlers that live inside layout controllers (FE-PERF-01).
   *
   * <p>{@code @ControllerAdvice} selects per controller <em>type</em>, so a {@code ResponseBody}
   * handler in a {@code @UsesLayoutModel} class is offered the layout model although it can never
   * render it. {@code LayoutContextLoader} now spares such a handler the backend read at runtime —
   * unless it reads a {@code ModelAttribute} parameter — so these handlers are no longer a cost,
   * but each one still runs the layout advices and still depends on that runtime test staying
   * right. The count may therefore only fall: a new JSON endpoint belongs in a
   * {@code @RestController}, which the advices never see. When a change moves handlers out, lower
   * {@link #BODY_HANDLERS_IN_LAYOUT_CONTROLLERS} to the new count in the same change.
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
   * The number of body-writing handlers inside {@code @UsesLayoutModel} controllers when the
   * ratchet was introduced (2026-09-23: 217, less the two catalog picker searches moved into a
   * {@code RestController} the same day). It may only be lowered.
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
   * The controllers that carry no {@code @PreAuthorize}, each because it must answer without a
   * session.
   *
   * <p>{@code AppLinkController} is the Android App Link's web-side fallback (REQ-SEC-038). It is
   * reached only when the link did not resolve to the app, which happens to a device whose domain
   * verification is in the sticky failed state and to any desktop browser — in both cases the
   * member is mid-login and may hold no session at all. A gate here would redirect into the OAuth2
   * entry point, which is the loop the route exists to break.
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
