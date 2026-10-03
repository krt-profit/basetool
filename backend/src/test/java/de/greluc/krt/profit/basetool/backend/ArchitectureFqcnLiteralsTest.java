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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Meta-test of the architecture tests (REQ-SEC-073): every fully qualified class name written as a
 * string literal in them resolves to a loadable class, so a moved or renamed class fails the build
 * instead of silently no longer matching.
 */
class ArchitectureFqcnLiteralsTest {

  /** A string literal, escapes included. */
  private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

  /** A fully qualified class name: at least two lower-case package segments and a type name. */
  private static final Pattern FQCN =
      Pattern.compile("(?<![\\w.$])((?:[a-z_][a-z0-9_]*\\.){2,}[A-Z][A-Za-z0-9_$]*)");

  /** The test sources, relative to the module directory Gradle runs the tests in. */
  private static final Path TEST_SOURCES = Path.of("src", "test", "java");

  @Test
  void everyFqcnLiteralInTheArchitectureTestsResolves() {
    List<Path> files = architectureTestSources();
    assertThat(files)
        .as("the architecture test sources this meta-test scans (selection floor)")
        .hasSizeGreaterThanOrEqualTo(59);
    List<String> unresolved = new ArrayList<>();
    for (Path file : files) {
      unresolvedFqcns(read(file)).forEach(name -> unresolved.add(file.getFileName() + ": " + name));
    }
    assertThat(unresolved)
        .as(
            "fully qualified class names in the architecture tests that no longer resolve; name the"
                + " class by class literal instead, or correct the name")
        .isEmpty();
  }

  @Test
  void anUnresolvableFqcnLiteralIsReported() {
    String missing = "com.example" + ".nothing.Missing";
    String source = "class X { String a = \"" + missing + "\"; String b = \"java.util.List\"; }";
    assertThat(unresolvedFqcns(source)).containsExactly(missing);
  }

  /**
   * Lists the source files of the architecture tests: the {@code Architecture*} test classes and
   * the planted-violation fixtures.
   *
   * @return the files
   */
  static List<Path> architectureTestSources() {
    try (Stream<Path> paths = Files.walk(TEST_SOURCES)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .filter(
              p ->
                  p.getFileName().toString().startsWith("Architecture")
                      || p.toString().replace('\\', '/').contains("/architecture/fixtures/"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns the fully qualified class names in the source's string literals that do not resolve.
   *
   * @param source Java source text
   * @return the unresolvable names, in order of appearance
   */
  static List<String> unresolvedFqcns(String source) {
    List<String> unresolved = new ArrayList<>();
    Matcher literal = STRING_LITERAL.matcher(source);
    while (literal.find()) {
      Matcher name = FQCN.matcher(literal.group(1));
      while (name.find()) {
        if (!resolves(name.group(1))) {
          unresolved.add(name.group(1));
        }
      }
    }
    return unresolved;
  }

  private static boolean resolves(String name) {
    try {
      Class.forName(name, false, ArchitectureFqcnLiteralsTest.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
