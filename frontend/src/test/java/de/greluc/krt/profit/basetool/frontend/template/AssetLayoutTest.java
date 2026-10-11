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

import de.greluc.krt.profit.basetool.frontend.support.AssetReferences;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeps the templates, scripts and stylesheets in the folder of their domain (plan §5.9, F5,
 * REQ-FE-034): a template below {@code templates/<domain>/}, a script below {@code
 * static/js/<domain>/}, an area stylesheet below {@code static/css/<domain>/} and a page stylesheet
 * below {@code static/css/pages/<domain>/}, where {@code <domain>} is a package of the frontend:
 * {@code kernel}, {@code shell} or a domain. Nothing sits flat. The five templates Spring Boot's
 * error view resolver finds by name are the one exception.
 */
class AssetLayoutTest {

  private static final Path JAVA_ROOT =
      Path.of("src", "main", "java", "de", "greluc", "krt", "profit", "basetool", "frontend");

  /** The templates Spring Boot resolves by status code or view name, which cannot move. */
  private static final Set<String> BY_CONVENTION =
      Set.of(
          "error.html", "error/403.html", "error/404.html", "error/500.html", "error/error.html");

  /** Selection floor: the frontend's packages when the rule was introduced. */
  private static final int DOMAIN_FLOOR = 23;

  /** Selection floors: files per tree when the rule was introduced. */
  private static final int TEMPLATE_FLOOR = 129;

  private static final int SCRIPT_FLOOR = 124;

  private static final int STYLESHEET_FLOOR = 88;

  @Test
  void everyTemplateScriptAndStylesheetLivesInItsDomainFolder() throws IOException {
    Set<String> domains = domains();
    assertThat(domains)
        .as("the frontend's domain packages")
        .hasSizeGreaterThanOrEqualTo(DOMAIN_FLOOR)
        .contains("kernel", "shell");

    Path resources = AssetReferences.MAIN_RESOURCES;
    assertThat(files(resources.resolve("templates"), ".html"))
        .hasSizeGreaterThanOrEqualTo(TEMPLATE_FLOOR);
    assertThat(files(resources.resolve("static/js"), ".js"))
        .hasSizeGreaterThanOrEqualTo(SCRIPT_FLOOR);
    assertThat(files(resources.resolve("static/css"), ".css"))
        .hasSizeGreaterThanOrEqualTo(STYLESHEET_FLOOR);
    assertThat(violations(resources, domains)).as("files outside the folder of a domain").isEmpty();
  }

  @Test
  void aFlatTemplateIsReported(@TempDir Path root) throws IOException {
    write(root.resolve("templates/bank/page.html"));
    write(root.resolve("templates/page.html"));
    write(root.resolve("templates/fragments/head.html"));
    write(root.resolve("templates/error.html"));

    assertThat(violations(root, Set.of("bank", "kernel")))
        .containsExactly(
            "templates/fragments/head.html: not below a domain folder",
            "templates/page.html: not below a domain folder");
  }

  @Test
  void aFlatScriptIsReported(@TempDir Path root) throws IOException {
    write(root.resolve("static/js/kernel/krt-fetch.js"));
    write(root.resolve("static/js/bank.js"));
    write(root.resolve("static/js/planted/x.js"));
    write(root.resolve("static/js/vendor/lib.js"));

    assertThat(violations(root, Set.of("bank", "kernel")))
        .containsExactly(
            "static/js/bank.js: not below a domain folder",
            "static/js/planted/x.js: not below a domain folder");
  }

  @Test
  void aFlatStylesheetIsReported(@TempDir Path root) throws IOException {
    write(root.resolve("static/css/kernel/styles.css"));
    write(root.resolve("static/css/bank/bank.css"));
    write(root.resolve("static/css/pages/bank/grants.css"));
    write(root.resolve("static/css/styles.css"));
    write(root.resolve("static/css/pages/grants.css"));
    write(root.resolve("static/css/pages/planted/x.css"));

    assertThat(violations(root, Set.of("bank", "kernel")))
        .containsExactly(
            "static/css/pages/grants.css: not below a domain folder",
            "static/css/pages/planted/x.css: not below a domain folder",
            "static/css/styles.css: not below a domain folder");
  }

  private static @NotNull List<String> violations(
      @NotNull Path resources, @NotNull Set<String> domains) throws IOException {
    List<String> violations = new ArrayList<>();
    for (String path : files(resources.resolve("templates"), ".html")) {
      if (!BY_CONVENTION.contains(path) && !inDomain(path.split("/"), 0, domains)) {
        violations.add("templates/" + path + ": not below a domain folder");
      }
    }
    for (String path : files(resources.resolve("static/js"), ".js")) {
      if (!path.startsWith("vendor/") && !inDomain(path.split("/"), 0, domains)) {
        violations.add("static/js/" + path + ": not below a domain folder");
      }
    }
    for (String path : files(resources.resolve("static/css"), ".css")) {
      String[] parts = path.split("/");
      int domainAt = "pages".equals(parts[0]) ? 1 : 0;
      if (!inDomain(parts, domainAt, domains)) {
        violations.add("static/css/" + path + ": not below a domain folder");
      }
    }
    return violations.stream().sorted().toList();
  }

  private static boolean inDomain(
      @NotNull String[] parts, int domainAt, @NotNull Set<String> domains) {
    return parts.length > domainAt + 1 && domains.contains(parts[domainAt]);
  }

  private static @NotNull Set<String> domains() {
    try (Stream<Path> entries = Files.list(JAVA_ROOT)) {
      Set<String> domains = new TreeSet<>();
      entries
          .filter(Files::isDirectory)
          .forEach(entry -> domains.add(entry.getFileName().toString()));
      return domains;
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private static @NotNull List<String> files(@NotNull Path root, @NotNull String suffix)
      throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(suffix))
          .map(path -> root.relativize(path).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }

  private static void write(@NotNull Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "x\n", StandardCharsets.UTF_8);
  }
}
