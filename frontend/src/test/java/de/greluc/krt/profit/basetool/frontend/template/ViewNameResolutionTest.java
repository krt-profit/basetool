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

package de.greluc.krt.profit.basetool.frontend.template;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.template.TemplateReferenceScan.ViewReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Resolves every view name and fragment selector the compiler cannot check (REQ-FE-026): each view
 * a controller or controller advice returns, each {@code "view :: fragment"} literal in Java, and
 * each literal {@code path :: fragment} reference between templates must name an existing template
 * that declares the fragment.
 */
class ViewNameResolutionTest {

  /** Selection floor: distinct view references per Java source when the test was added. */
  private static final int MIN_JAVA_REFERENCES = 182;

  /** Selection floor: literal fragment references between templates when the test was added. */
  private static final int MIN_TEMPLATE_REFERENCES = 486;

  /**
   * The templates no Java source or template names, because Spring Boot's error view resolver picks
   * them by status code.
   */
  private static final Set<String> RESOLVED_BY_CONVENTION =
      Set.of("error", "error/403", "error/404", "error/500");

  @Test
  void everyViewAJavaSourceNamesResolves() {
    List<ViewReference> references = javaReferences();

    assertThat(references)
        .as("selection floor: the scan must find at least today's view references")
        .hasSizeGreaterThanOrEqualTo(MIN_JAVA_REFERENCES);
    assertThat(unresolved(references, TemplateReferenceScan.templates()))
        .as("view names or fragments a handler hands to Thymeleaf that do not exist")
        .isEmpty();
  }

  @Test
  void everyLiteralFragmentReferenceBetweenTemplatesResolves() {
    List<ViewReference> references = templateReferences();

    assertThat(references)
        .as("selection floor: the scan must find at least today's fragment references")
        .hasSizeGreaterThanOrEqualTo(MIN_TEMPLATE_REFERENCES);
    assertThat(unresolved(references, TemplateReferenceScan.templates()))
        .as("fragment references between templates that do not exist")
        .isEmpty();
  }

  /**
   * Keeps the scan honest: a template nothing names is either dead or named in a way the scan does
   * not recognise, and in the second case the checks above would miss it.
   */
  @Test
  void everyTemplateIsNamedByAScannedReference() {
    Set<String> named = new TreeSet<>(RESOLVED_BY_CONVENTION);
    javaReferences().forEach(reference -> named.add(reference.view()));
    templateReferences().forEach(reference -> named.add(reference.view()));
    Set<String> unnamed = new TreeSet<>();
    for (String template : TemplateReferenceScan.templates().keySet()) {
      String view = template.substring(0, template.length() - ".html".length());
      if (!named.contains(view)) {
        unnamed.add(view);
      }
    }

    assertThat(unnamed)
        .as(
            "templates no scanned reference names; return the view as a literal, or teach"
                + " TemplateReferenceScan the new shape")
        .isEmpty();
  }

  /**
   * Every view reference of every main Java source.
   *
   * @return the references
   */
  private static List<ViewReference> javaReferences() {
    List<ViewReference> references = new ArrayList<>();
    for (Map.Entry<String, String> source : TemplateReferenceScan.javaSources().entrySet()) {
      references.addAll(
          TemplateReferenceScan.javaViewReferences(source.getKey(), source.getValue()));
    }
    return references;
  }

  /**
   * Every literal fragment reference of every template.
   *
   * @return the references
   */
  private static List<ViewReference> templateReferences() {
    List<ViewReference> references = new ArrayList<>();
    for (Map.Entry<String, String> template : TemplateReferenceScan.templates().entrySet()) {
      references.addAll(
          TemplateReferenceScan.templateViewReferences(template.getKey(), template.getValue()));
    }
    return references;
  }

  @Test
  void aMovedTemplateOrARenamedFragmentIsReported() {
    String controller =
        """
        @Controller
        class FixtureController {
          String page() { return "fixture-page"; }
          String moved() { return "fixture/moved"; }
          String part(boolean f) { return f ? "fixture-page :: rows" : "fixture-page"; }
          String gone() { return "fixture-page :: renamedRows"; }
          String away() { return "redirect:/fixture"; }
          String key() { return "fixture.error.key"; }
          /** Picks the view; @return the name of the view */
          String pick(String f) {
            String layout = "table".equals(f) ? "table" : "card";
            return switch (f) {
              case "a" -> "fixture/switched";
              default -> "fixture-page";
            };
          }
        }
        """;
    String page =
        """
        <div th:fragment="rows"></div>
        <div th:replace="~{fragments/missing :: x}"></div>
        <div th:replace="~{fixture-page :: rows}"></div>
        """;
    Map<String, String> templates = Map.of("fixture-page.html", page);

    List<ViewReference> java = TemplateReferenceScan.javaViewReferences("Fixture.java", controller);
    List<ViewReference> html = TemplateReferenceScan.templateViewReferences("fixture-page", page);

    assertThat(unresolved(java, templates))
        .containsExactlyInAnyOrder(
            "Fixture.java: fixture/moved (no template fixture/moved.html)",
            "Fixture.java: fixture/switched (no template fixture/switched.html)",
            "Fixture.java: fixture-page :: renamedRows"
                + " (template fixture-page.html declares no fragment renamedRows)");
    assertThat(java).hasSize(5);
    assertThat(unresolved(html, templates))
        .containsExactly(
            "fixture-page: fragments/missing :: x (no template fragments/missing.html)");
  }

  @Test
  void aReturnedLiteralOutsideAControllerIsNotAView() {
    String service =
        """
        @Service
        class FixtureService {
          String status() { return "fixture-page"; }
        }
        """;

    assertThat(TemplateReferenceScan.javaViewReferences("FixtureService.java", service)).isEmpty();
  }

  /**
   * Resolves each reference and reports the failing ones.
   *
   * @param references the references
   * @param templates every template by path
   * @return {@code reference (reason)} per unresolved reference
   */
  private static List<String> unresolved(
      List<ViewReference> references, Map<String, String> templates) {
    List<String> broken = new ArrayList<>();
    for (ViewReference reference : references) {
      String reason = TemplateReferenceScan.unresolved(reference, templates);
      if (reason != null) {
        broken.add(reference + " (" + reason + ")");
      }
    }
    return broken;
  }
}
