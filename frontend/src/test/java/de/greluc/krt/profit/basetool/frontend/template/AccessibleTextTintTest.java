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
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build-time check of the accessible text tints (REQ-UI-006): text colour never uses the danger
 * ({@code #A3000A}), info ({@code #355DDC}) or grey 2 ({@code #646464}) hues, which fail WCAG AA on
 * dark surfaces, but their {@code -text} tints. Reads the declarations directly, so unrendered
 * styles are covered too.
 */
class AccessibleTextTintTest {

  /** The {@code color} property (not {@code border-color} etc.) set to a failing hue. */
  private static final Pattern CSS_TEXT_COLOUR =
      Pattern.compile(
          "(?<![-\\w])color\\s*:\\s*(var\\(--color-(?:danger|info|gray-2)\\)"
              + "|#a3000a|#355ddc|#646464)(?![-\\w])",
          Pattern.CASE_INSENSITIVE);

  /** An innermost CSS rule block: its selector and its declarations. */
  private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)\\}");

  /**
   * The rules allowed to keep canonical Grau 2, as {@code "<file> <selector>"}. REQ-UI-006 keeps
   * the canonical grey for purely decorative glyphs; both of these are {@code aria-hidden}: the
   * unsorted-column indicator on the material demand table (the header's {@code aria-sort} carries
   * the state) and the search icon inside the labelled blueprint search field.
   */
  private static final Set<String> DECORATIVE =
      Set.of(
          "styles.css .demand-sortable[aria-sort='none'] .demand-sort-indicator",
          "personal-inventory.css .krt-bp-search-icon");

  /** A script writing a failing hue into {@code style.color}. */
  private static final Pattern JS_TEXT_COLOUR =
      Pattern.compile(
          "\\.style\\.color\\s*=\\s*['\"](var\\(--color-(?:danger|info|gray-2)\\)"
              + "|#a3000a|#355ddc|#646464)['\"]",
          Pattern.CASE_INSENSITIVE);

  /**
   * Asserts that no stylesheet and no script sets a failing canonical hue as a text colour.
   *
   * @throws IOException if a file cannot be read
   * @throws URISyntaxException if a classpath root cannot be resolved
   */
  @Test
  void noTextTakesAHueThatFailsContrast() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    int stylesheets = 0;
    int exempted = 0;
    for (Path sheet : filesUnder("/static/css/styles.css", ".css")) {
      stylesheets++;
      String css =
          Files.readString(sheet, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ");
      Matcher rule = RULE.matcher(css);
      while (rule.find()) {
        Matcher colour = CSS_TEXT_COLOUR.matcher(rule.group(2));
        while (colour.find()) {
          String selector = rule.group(1).strip().replaceAll("\\s+", " ");
          if (DECORATIVE.contains(sheet.getFileName() + " " + selector)) {
            exempted++;
          } else {
            offenders.add(sheet.getFileName() + " -> " + selector + " { " + colour.group() + " }");
          }
        }
      }
    }
    assertThat(exempted)
        .as("every DECORATIVE exemption still names a rule that exists")
        .isEqualTo(DECORATIVE.size());
    for (Path script : filesUnder("/static/js/krt-modal.js", ".js")) {
      String js = Files.readString(script, StandardCharsets.UTF_8);
      collect(script, js, JS_TEXT_COLOUR, offenders);
    }
    assertThat(stylesheets).as("the stylesheets are still there to be checked").isGreaterThan(20);
    assertThat(offenders)
        .as(
            "REQ-UI-006: text takes --color-danger-text / --color-info-text / --color-gray-2-text;"
                + " the canonical hues are for fills, borders and icons behind text")
        .isEmpty();
  }

  /**
   * Adds one entry per match of {@code pattern} in {@code source}.
   *
   * @param file the file the source came from, for the message
   * @param source the file's text
   * @param pattern what to look for
   * @param offenders where each match is recorded
   */
  private static void collect(Path file, String source, Pattern pattern, List<String> offenders) {
    Matcher match = pattern.matcher(source);
    while (match.find()) {
      offenders.add(file.getFileName() + " -> " + match.group());
    }
  }

  /**
   * Walks the directory holding {@code anchorResource} and returns every file with {@code
   * extension}.
   *
   * @param anchorResource a classpath resource whose parent directory is the root to walk
   * @param extension the file suffix to keep
   * @return the matching files, sorted
   * @throws IOException if the tree cannot be walked
   * @throws URISyntaxException if the anchor cannot be resolved to a path
   */
  private static List<Path> filesUnder(String anchorResource, String extension)
      throws IOException, URISyntaxException {
    URL anchor = AccessibleTextTintTest.class.getResource(anchorResource);
    assertThat(anchor).as("%s classpath resource", anchorResource).isNotNull();
    Path root = Paths.get(anchor.toURI()).getParent();
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(extension))
          .sorted()
          .toList();
    }
  }
}
