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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build-time check that every {@code var(--x)} in the shipped stylesheets that has no fallback names
 * a custom property something declares: a stylesheet declaration, a script that sets it at runtime
 * or a template that sets it inline. An undefined property makes the declaration invalid at
 * computed-value time and silently drops it (REQ-UI-001).
 *
 * <p>{@link #ALLOWED_UNDEFINED} is the explicit allow-list for properties that are legitimately
 * supplied from outside the shipped CSS; it is empty, and an entry needs a reason in this Javadoc.
 */
class CustomPropertyExistenceTest {

  /** Properties allowed to stay undeclared in the shipped sources; none today. */
  private static final Set<String> ALLOWED_UNDEFINED = Set.of();

  private static final Pattern DECLARATION =
      Pattern.compile("(?<![\\w-])(--[A-Za-z0-9_-]+)\\s*:");

  private static final Pattern SCRIPT_NAME = Pattern.compile("['\"`](--[A-Za-z0-9_-]+)['\"`]");

  private static final Pattern USAGE = Pattern.compile("var\\(\\s*(--[A-Za-z0-9_-]+)\\s*([,)])");

  /**
   * Asserts that no fallback-free {@code var(--x)} in {@code static/css} names an undeclared
   * property.
   *
   * @throws IOException if a source cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void everyFallbackFreeVarNamesADeclaredProperty() throws IOException, URISyntaxException {
    Path resources = resourcesRoot();
    Set<String> declared = new TreeSet<>();
    for (Path file : files(resources.resolve("static/css"), ".css")) {
      collect(DECLARATION, stripComments(Files.readString(file, StandardCharsets.UTF_8)), declared);
    }
    for (Path file : files(resources.resolve("static/js"), ".js")) {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      collect(SCRIPT_NAME, source, declared);
      collect(DECLARATION, source, declared);
    }
    for (Path file : files(resources.resolve("templates"), ".html")) {
      collect(DECLARATION, Files.readString(file, StandardCharsets.UTF_8), declared);
    }
    assertThat(declared).as("custom properties are declared at all").hasSizeGreaterThan(50);

    List<String> offenders = new ArrayList<>();
    for (Path file : files(resources.resolve("static/css"), ".css")) {
      String css = stripComments(Files.readString(file, StandardCharsets.UTF_8));
      Matcher usage = USAGE.matcher(css);
      while (usage.find()) {
        String name = usage.group(1);
        boolean hasFallback = ",".equals(usage.group(2));
        if (!hasFallback && !declared.contains(name) && !ALLOWED_UNDEFINED.contains(name)) {
          offenders.add(resources.resolve("static/css").relativize(file) + ": var(" + name + ")");
        }
      }
    }
    assertThat(offenders)
        .as("REQ-UI-001: every fallback-free var(--x) names a declared custom property")
        .isEmpty();
  }

  private static void collect(Pattern pattern, String text, Set<String> into) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      into.add(matcher.group(1));
    }
  }

  private static String stripComments(String css) {
    return css.replaceAll("(?s)/\\*.*?\\*/", " ");
  }

  private static Path resourcesRoot() throws URISyntaxException {
    URL anchor = CustomPropertyExistenceTest.class.getResource("/static/css/styles.css");
    assertThat(anchor).as("/static/css/styles.css classpath resource").isNotNull();
    return Paths.get(anchor.toURI()).getParent().getParent().getParent();
  }

  private static List<Path> files(Path root, String suffix) throws IOException {
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(suffix))
          .sorted()
          .toList();
    }
  }
}
