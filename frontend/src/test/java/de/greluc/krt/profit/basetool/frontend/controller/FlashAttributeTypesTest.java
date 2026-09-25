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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no controller puts a {@code BindingResult} into a flash attribute (REQ-SEC-049),
 * since the session serializer cannot read one back and the whole flash map would be dropped.
 *
 * <p>Scans every {@code addFlashAttribute(…)} call in the main sources, with a floor on the number
 * of calls found.
 */
class FlashAttributeTypesTest {

  /**
   * Fewer calls than this means the parser broke, not that the frontend flashes less: there are
   * about three hundred in the main sources.
   */
  private static final int MIN_CALLS = 150;

  private static final String CALL = "addFlashAttribute(";

  @Test
  void noControllerFlashesABindingResult() throws IOException {
    List<String> calls = new ArrayList<>();
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(frontendMainSources())) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        for (String call : flashCalls(Files.readString(file))) {
          calls.add(call);
          if (namesABindingResult(call)) {
            offenders.add(file.getFileName() + ": " + call);
          }
        }
      }
    }

    assertThat(calls)
        .as("the scan found too few addFlashAttribute calls; the parser is broken")
        .hasSizeGreaterThanOrEqualTo(MIN_CALLS);
    assertThat(offenders)
        .as(
            "a BindingResult in a flash attribute is written to the session but can never be read"
                + " back, so the whole flash map is dropped on the redirect; re-render the view"
                + " inline instead, as PersonalInventoryPageController does")
        .isEmpty();
  }

  @Test
  void theParserSeesEveryShapeTheOldCodeUsed() {
    String source =
        """
        redirectAttributes.addFlashAttribute(
            "org.springframework.validation.BindingResult.personalInventoryForm", bindingResult);
        redirectAttributes.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + "form", errors);
        redirectAttributes.addFlashAttribute("errorToast", classify(e, "x.y"));
        """;

    List<String> calls = flashCalls(source);

    assertThat(calls).hasSize(3);
    assertThat(calls.stream().filter(FlashAttributeTypesTest::namesABindingResult)).hasSize(2);
  }

  /**
   * Extracts the argument text of every {@code addFlashAttribute(…)} call, balancing parentheses so
   * a nested call in the arguments does not end it early.
   *
   * @param source a Java source file's text.
   * @return one entry per call, whitespace collapsed.
   */
  private static List<String> flashCalls(String source) {
    List<String> calls = new ArrayList<>();
    int from = 0;
    while (true) {
      int start = source.indexOf(CALL, from);
      if (start < 0) {
        return calls;
      }
      int depth = 1;
      int i = start + CALL.length();
      while (i < source.length() && depth > 0) {
        char c = source.charAt(i);
        if (c == '(') {
          depth++;
        } else if (c == ')') {
          depth--;
        }
        i++;
      }
      calls.add(source.substring(start + CALL.length(), i - 1).replaceAll("\\s+", " ").trim());
      from = i;
    }
  }

  /**
   * Whether a call's arguments name a binding result.
   *
   * @param arguments the argument text of one {@code addFlashAttribute} call.
   * @return {@code true} when it flashes a binding result.
   */
  private static boolean namesABindingResult(String arguments) {
    return arguments.contains("BindingResult") || arguments.contains("bindingResult");
  }

  /**
   * The frontend's main Java sources: Gradle runs the tests from {@code frontend/}, an IDE may run
   * them from the repository root.
   *
   * @return the directory holding the {@code de/} package tree.
   */
  private static Path frontendMainSources() {
    Path relative = Paths.get("src", "main", "java");
    if (Files.isDirectory(relative.resolve("de"))
        && Files.exists(Paths.get("src", "main", "resources", "templates"))) {
      return relative;
    }
    return Paths.get("frontend").resolve(relative);
  }
}
