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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Build-time check that every {@code var(--x)} without a fallback names a custom property something
 * declares: a stylesheet declaration, a script that sets it at runtime or a template that sets it
 * inline. The usages are read from the stylesheets, the templates (inline and {@code th:style}
 * values) and the scripts. An undefined property makes the declaration invalid at computed-value
 * time and silently drops it (REQ-UI-001).
 *
 * <p>{@link #ALLOWED_UNDEFINED} is the explicit allow-list for properties that are legitimately
 * supplied from outside the shipped sources; it is empty, and an entry needs a reason in this
 * Javadoc.
 */
class CustomPropertyExistenceTest {

  /** Properties allowed to stay undeclared in the shipped sources; none today. */
  private static final Set<String> ALLOWED_UNDEFINED = Set.of();

  private static final Pattern DECLARATION = Pattern.compile("(?<![\\w-])(--[A-Za-z0-9_-]+)\\s*:");

  private static final Pattern SCRIPT_NAME = Pattern.compile("['\"`](--[A-Za-z0-9_-]+)['\"`]");

  private static final Pattern USAGE = Pattern.compile("var\\(\\s*(--[A-Za-z0-9_-]+)\\s*([,)])");

  /**
   * The outcome of one scan.
   *
   * @param usages the number of {@code var(--x)} usages read
   * @param offenders one line per fallback-free usage of an undeclared property
   */
  record Scan(int usages, @NotNull List<String> offenders) {}

  /**
   * Asserts that no fallback-free {@code var(--x)} in the shipped stylesheets, templates or scripts
   * names an undeclared property.
   *
   * @throws IOException if a source cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void everyFallbackFreeVarNamesADeclaredProperty() throws IOException, URISyntaxException {
    Scan scan = scan(CssSources.mainResources());
    assertThat(scan.usages()).as("var() usages are read at all").isGreaterThan(3000);
    assertThat(scan.offenders())
        .as("REQ-UI-001: every fallback-free var(--x) names a declared custom property")
        .isEmpty();
  }

  /**
   * Asserts that the scan reports an undeclared property planted in a stylesheet, a template's
   * inline style and a script, and leaves a declared one and one with a fallback alone.
   *
   * @throws IOException if a fixture cannot be read
   * @throws URISyntaxException if the fixture root cannot be resolved
   */
  @Test
  void aPlantedUndeclaredPropertyIsReported() throws IOException, URISyntaxException {
    Scan scan = scan(CssSources.fixture("undefined-token"));
    assertThat(scan.offenders())
        .containsExactlyInAnyOrder(
            "static/css/pages/planted.css: var(--planted-in-css)",
            "templates/planted.html: var(--planted-in-template)",
            "static/js/planted.js: var(--planted-in-script)");
  }

  /**
   * Scans one resources tree.
   *
   * @param resources a directory holding {@code static/css}, {@code static/js} and {@code
   *     templates}
   * @return the usage count and the offenders
   * @throws IOException if a source cannot be read
   */
  static @NotNull Scan scan(@NotNull Path resources) throws IOException {
    List<Path> css = CssSources.files(resources.resolve("static/css"), ".css");
    List<Path> js = CssSources.files(resources.resolve("static/js"), ".js");
    List<Path> html = CssSources.files(resources.resolve("templates"), ".html");
    Set<String> declared = new TreeSet<>();
    for (Path file : css) {
      collect(DECLARATION, CssSources.stripComments(read(file)), declared);
    }
    for (Path file : js) {
      String source = read(file);
      collect(SCRIPT_NAME, source, declared);
      collect(DECLARATION, source, declared);
    }
    for (Path file : html) {
      collect(DECLARATION, read(file), declared);
    }
    int usages = 0;
    List<String> offenders = new ArrayList<>();
    List<Path> sources = new ArrayList<>(css);
    sources.addAll(html);
    sources.addAll(js);
    for (Path file : sources) {
      String text = read(file);
      if (file.getFileName().toString().endsWith(".css")) {
        text = CssSources.stripComments(text);
      }
      Matcher usage = USAGE.matcher(text);
      while (usage.find()) {
        usages++;
        String name = usage.group(1);
        boolean hasFallback = ",".equals(usage.group(2));
        if (!hasFallback && !declared.contains(name) && !ALLOWED_UNDEFINED.contains(name)) {
          String path = resources.relativize(file).toString().replace('\\', '/');
          offenders.add(path + ": var(" + name + ")");
        }
      }
    }
    return new Scan(usages, offenders);
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  private static void collect(Pattern pattern, String text, Set<String> into) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      into.add(matcher.group(1));
    }
  }
}
