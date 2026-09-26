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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build-time check that every stylesheet declares the one cascade layer order and places every rule
 * inside a layer it may use (REQ-UI-024, ADR-0212), since unlayered rules beat every layer.
 */
class CascadeLayerOrderTest {

  /** The one order, restated at the top of every file. */
  static final String ORDER = "@layer base, components, page, migration, utilities;";

  /** The layers a file may use; any file not named here is a page stylesheet. */
  private static final Map<String, Set<String>> ALLOWED =
      Map.of(
          "styles.css", Set.of("base", "components", "page"),
          "inline-migration.css", Set.of("migration", "utilities"));

  private static final Set<String> PAGE_ONLY = Set.of("page");

  /**
   * Asserts the order statement and the layer of every top-level block in every stylesheet.
   *
   * @throws IOException if a stylesheet cannot be read
   * @throws URISyntaxException if the CSS classpath root cannot be resolved
   */
  @Test
  void everyStylesheetDeclaresTheOrderAndLayersEveryRule() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    List<Path> sheets = stylesheets();
    for (Path sheet : sheets) {
      String name = sheet.getFileName().toString();
      String css =
          Files.readString(sheet, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ");
      Set<String> allowed = ALLOWED.getOrDefault(name, PAGE_ONLY);
      List<String> statements = topLevel(css);
      if (statements.isEmpty() || !ORDER.equals(statements.get(0))) {
        offenders.add(name + ": does not start with `" + ORDER + "`");
      }
      Set<String> used = new LinkedHashSet<>();
      for (String statement :
          statements.subList(Math.min(1, statements.size()), statements.size())) {
        Matcher block = Pattern.compile("^@layer\\s+([\\w-]+)\\s*\\{").matcher(statement);
        if (!block.find()) {
          offenders.add(name + ": a rule outside any layer: " + abbreviate(statement));
        } else if (!allowed.contains(block.group(1))) {
          offenders.add(name + ": uses layer `" + block.group(1) + "`, allowed " + allowed);
        } else {
          used.add(block.group(1));
        }
      }
      if (used.isEmpty()) {
        offenders.add(name + ": declares no layer block at all");
      }
    }
    assertThat(sheets).as("the stylesheets are still there to be checked").hasSizeGreaterThan(20);
    assertThat(offenders)
        .as("REQ-UI-024: every rule sits in a cascade layer, in the one declared order")
        .isEmpty();
  }

  /**
   * Asserts that the two runtime state classes are declared only in the {@code utilities} layer, so
   * they beat every component, page and migrated rule by layer alone.
   *
   * @throws IOException if a stylesheet cannot be read
   * @throws URISyntaxException if the CSS classpath root cannot be resolved
   */
  @Test
  void theStateClassesSitInTheUtilitiesLayerAndNowhereElse()
      throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    boolean hiddenInUtilities = false;
    boolean openInUtilities = false;
    Pattern stateSelector = Pattern.compile("\\.krtm-(hidden|modal-open)(?![-\\w])");
    for (Path sheet : stylesheets()) {
      String name = sheet.getFileName().toString();
      String css =
          Files.readString(sheet, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ");
      for (String statement : topLevel(css)) {
        if (!stateSelector.matcher(statement).find()) {
          continue;
        }
        if ("inline-migration.css".equals(name) && statement.startsWith("@layer utilities")) {
          hiddenInUtilities |= statement.contains(".krtm-hidden");
          openInUtilities |= statement.contains(".krtm-modal-open");
        } else {
          offenders.add(name + ": names a state class outside the utilities layer");
        }
      }
    }
    assertThat(hiddenInUtilities).as(".krtm-hidden is declared in @layer utilities").isTrue();
    assertThat(openInUtilities).as(".krtm-modal-open is declared in @layer utilities").isTrue();
    assertThat(offenders)
        .as("REQ-UI-024: the state classes win by layer; no stylesheet re-asserts them")
        .isEmpty();
  }

  /**
   * Splits a comment-free stylesheet into its top-level statements: an at-rule ending in {@code ;}
   * or a block from its prelude to the matching closing brace.
   *
   * @param css the stylesheet with its comments removed
   * @return the statements, trimmed, in source order
   */
  static List<String> topLevel(String css) {
    List<String> statements = new ArrayList<>();
    int depth = 0;
    int start = 0;
    char quote = 0;
    for (int i = 0; i < css.length(); i++) {
      char c = css.charAt(i);
      if (quote != 0) {
        if (c == '\\') {
          i++;
        } else if (c == quote) {
          quote = 0;
        }
        continue;
      }
      if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          statements.add(css.substring(start, i + 1).strip());
          start = i + 1;
        }
      } else if (c == ';' && depth == 0) {
        statements.add(css.substring(start, i + 1).strip());
        start = i + 1;
      }
    }
    if (!css.substring(start).isBlank()) {
      statements.add(css.substring(start).strip());
    }
    return statements;
  }

  private static String abbreviate(String statement) {
    String oneLine = statement.replaceAll("\\s+", " ");
    return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
  }

  /**
   * Resolves every stylesheet on the test classpath.
   *
   * @return every {@code .css} file under the static CSS root, sorted
   * @throws IOException if the tree cannot be walked
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  private static List<Path> stylesheets() throws IOException, URISyntaxException {
    URL anchor = CascadeLayerOrderTest.class.getResource("/static/css/styles.css");
    assertThat(anchor).as("/static/css/styles.css classpath resource").isNotNull();
    Path root = Paths.get(anchor.toURI()).getParent();
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".css"))
          .sorted()
          .toList();
    }
  }
}
