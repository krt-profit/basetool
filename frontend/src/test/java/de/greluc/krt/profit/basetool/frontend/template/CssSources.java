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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/**
 * Locates the shipped web assets, or a planted fixture tree of the same shape, for the CSS token
 * guards.
 */
final class CssSources {

  private CssSources() {}

  /**
   * Resolves the main resources root, the directory holding {@code static/} and {@code templates/}.
   *
   * @return the resources root on the test classpath
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  static @NotNull Path mainResources() throws URISyntaxException {
    URL anchor = CssSources.class.getResource("/static/css/styles.css");
    assertThat(anchor).as("/static/css/styles.css classpath resource").isNotNull();
    return Paths.get(anchor.toURI()).getParent().getParent().getParent();
  }

  /**
   * Resolves a planted fixture tree under {@code css-guard-fixtures/} on the test classpath.
   *
   * @param name the fixture directory name
   * @return the fixture root, shaped like the resources root
   * @throws URISyntaxException if the fixture root cannot be resolved
   */
  static @NotNull Path fixture(@NotNull String name) throws URISyntaxException {
    URL anchor = CssSources.class.getResource("/css-guard-fixtures/" + name);
    assertThat(anchor).as("fixture " + name).isNotNull();
    return Paths.get(anchor.toURI());
  }

  /**
   * Lists the regular files below a directory whose name ends in a suffix.
   *
   * @param root the directory to walk; a missing one yields no files
   * @param suffix the file-name suffix, such as {@code .css}
   * @return the matching files, sorted
   * @throws IOException if the tree cannot be walked
   */
  static @NotNull List<Path> files(@NotNull Path root, @NotNull String suffix) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(suffix))
          .sorted()
          .toList();
    }
  }

  /**
   * Removes every block comment from a stylesheet.
   *
   * @param css the stylesheet source
   * @return the source with each comment replaced by a space
   */
  static @NotNull String stripComments(@NotNull String css) {
    return css.replaceAll("(?s)/\\*.*?\\*/", " ");
  }

  /**
   * Parses an integer read from a stylesheet, failing the test with the source context when it is
   * not one.
   *
   * @param text the digits a pattern matched
   * @param context where the value was read, for the failure message
   * @return the parsed value
   * @throws AssertionError if the text is not an integer in range
   */
  static int parseInt(@NotNull String text, @NotNull String context) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw new AssertionError(context + ": `" + text + "` is not an integer in range", e);
    }
  }
}
