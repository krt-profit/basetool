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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build-time check that the retired {@code inline-migration.css} stays retired: no stylesheet of
 * that name exists, and no template or script names a {@code krtm-*} class (REQ-UI-027). The {@code
 * data-krtm-width} attribute is the CSP-safe width mechanism, not a class, and is allowed.
 */
class NoMigrationClassTest {

  private static final Pattern MIGRATION_CLASS = Pattern.compile("(?<![\\w-])krtm-[\\w-]+");

  /**
   * Asserts that the migration stylesheet is gone from the static CSS root.
   *
   * @throws URISyntaxException if the CSS classpath root cannot be resolved
   */
  @Test
  void theMigrationStylesheetIsGone() throws URISyntaxException {
    assertThat(
            Files.exists(resource("/static/css/styles.css").resolveSibling("inline-migration.css")))
        .as("inline-migration.css is retired")
        .isFalse();
  }

  /**
   * Asserts that no template and no script names a {@code krtm-*} class.
   *
   * @throws IOException if a file cannot be read
   * @throws URISyntaxException if a classpath root cannot be resolved
   */
  @Test
  void noTemplateOrScriptNamesAMigrationClass() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    List<Path> files = new ArrayList<>();
    files.addAll(
        files(resource("/templates/fragments/head.html").getParent().getParent(), ".html"));
    files.addAll(files(resource("/static/js/krt-modal.js").getParent(), ".js"));
    for (Path file : files) {
      Matcher match = MIGRATION_CLASS.matcher(Files.readString(file, StandardCharsets.UTF_8));
      while (match.find()) {
        offenders.add(file.getFileName() + ": " + match.group());
      }
    }
    assertThat(files)
        .as("the templates and scripts are still there to be checked")
        .hasSizeGreaterThan(50);
    assertThat(offenders)
        .as(
            "REQ-UI-027: use the design system's building blocks or a page rule, not a krtm-*"
                + " class")
        .isEmpty();
  }

  private static Path resource(String name) throws URISyntaxException {
    URL anchor = NoMigrationClassTest.class.getResource(name);
    assertThat(anchor).as(name + " classpath resource").isNotNull();
    return Paths.get(anchor.toURI());
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
