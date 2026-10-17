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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Holds the broad {@code catch} clauses of the frontend's {@code web} packages to a frozen count
 * per file that may only shrink (REQ-FE-036).
 *
 * <p>A broad clause catches {@code Exception}, {@code RuntimeException} or {@code Throwable}, alone
 * or in a multi-catch. A file above its frozen count fails the build; a file below it fails too
 * until the count in {@code architecture/broad-catch-ratchet.properties} is lowered, so the list
 * never holds headroom. The counter is proven against planted sources.
 */
class BroadCatchRatchetTest {

  private static final String PACKAGE_ROOT = "src/main/java/de/greluc/krt/profit/basetool/frontend";

  private static final String FROZEN_RESOURCE = "/architecture/broad-catch-ratchet.properties";

  private static final Set<String> BROAD_TYPES =
      Set.of(
          "Exception",
          "RuntimeException",
          "Throwable",
          "java.lang.Exception",
          "java.lang.RuntimeException",
          "java.lang.Throwable");

  private static final Pattern CATCH_CLAUSE =
      Pattern.compile(
          "catch\\s*\\(\\s*(?:final\\s+)?([\\w.]+(?:\\s*\\|\\s*[\\w.]+)*)\\s+\\w+\\s*\\)");

  @Test
  void noWebFileCatchesBroaderThanItsFrozenCount() throws IOException {
    Map<String, Integer> actual = countWebSources(resolveModuleRelative(PACKAGE_ROOT));
    Map<String, Integer> frozen = loadFrozen();

    Map<String, String> grown = new TreeMap<>();
    actual.forEach(
        (file, count) -> {
          int allowed = frozen.getOrDefault(file, 0);
          if (count > allowed) {
            grown.put(file, count + " > " + allowed);
          }
        });
    assertThat(grown)
        .as(
            "web files with more broad catch clauses than frozen; let the failure reach"
                + " BackendErrorMapper and the GlobalExceptionHandler, or catch the specific"
                + " exception, instead of raising the count")
        .isEmpty();

    Map<String, String> shrunk = new TreeMap<>();
    frozen.forEach(
        (file, allowed) -> {
          int count = actual.getOrDefault(file, 0);
          if (count < allowed) {
            shrunk.put(file, count + " < " + allowed);
          }
        });
    assertThat(shrunk)
        .as("web files below their frozen count; lower the entry (delete it at 0)")
        .isEmpty();
  }

  @Test
  void theCounterFindsEveryBroadClauseAndNoNarrowOne() {
    String source =
        String.join(
            "\n",
            "try { a(); } catch (BackendServiceException e) { b(); }",
            "catch (Exception e) { c(); }",
            "catch (final RuntimeException ex) { d(); }",
            "catch (IllegalStateException | Throwable _) { e(); }",
            "catch (java.lang.Exception ignored) { f(); }",
            "catch (NumberFormatException e) { g(); }",
            "catch (ExceptionInInitializerError e) { h(); }");

    assertThat(countBroadClauses(source)).isEqualTo(4);
  }

  @Test
  void onlyWebPackagesAreCounted() {
    assertThat(isWebSource("inventory/web/InventoryPageController.java")).isTrue();
    assertThat(isWebSource("kernel/web/BackendErrorResponses.java")).isTrue();
    assertThat(isWebSource("kernel/backend/BackendApiClient.java")).isFalse();
    assertThat(isWebSource("bank/model/BankAccountDto.java")).isFalse();
  }

  /**
   * Counts the broad catch clauses of every {@code web}-package source under the package root.
   *
   * @param root the frontend package root
   * @return the non-zero counts by root-relative path with forward slashes
   * @throws IOException when the tree cannot be walked
   */
  private static Map<String, Integer> countWebSources(Path root) throws IOException {
    assertThat(Files.isDirectory(root)).as("source root not found at " + root).isTrue();
    Map<String, Integer> counts = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      files
          .filter(Files::isRegularFile)
          .forEach(
              file -> {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!isWebSource(relative)) {
                  return;
                }
                int count = countBroadClauses(read(file));
                if (count > 0) {
                  counts.put(relative, count);
                }
              });
    }
    return counts;
  }

  /**
   * Decides whether a root-relative path names a Java source inside a {@code web} package.
   *
   * @param relative the path relative to the frontend package root, with forward slashes
   * @return {@code true} for a {@code .java} file with a {@code web} directory segment
   */
  private static boolean isWebSource(String relative) {
    if (!relative.endsWith(".java")) {
      return false;
    }
    String[] segments = relative.split("/");
    return Arrays.asList(segments).subList(0, segments.length - 1).contains("web");
  }

  /**
   * Counts the catch clauses in a source whose caught types include a broad one.
   *
   * @param source the Java source text
   * @return the number of broad clauses
   */
  private static int countBroadClauses(String source) {
    Matcher matcher = CATCH_CLAUSE.matcher(source);
    int count = 0;
    while (matcher.find()) {
      boolean broad =
          Arrays.stream(matcher.group(1).split("\\|"))
              .map(String::trim)
              .anyMatch(BROAD_TYPES::contains);
      if (broad) {
        count++;
      }
    }
    return count;
  }

  /**
   * Loads the frozen counts.
   *
   * @return the frozen count by root-relative path
   * @throws IOException when the resource cannot be read
   */
  private static Map<String, Integer> loadFrozen() throws IOException {
    Properties properties = new Properties();
    try (InputStream in = BroadCatchRatchetTest.class.getResourceAsStream(FROZEN_RESOURCE)) {
      assertThat(in).as(FROZEN_RESOURCE + " missing").isNotNull();
      properties.load(in);
    }
    Map<String, Integer> frozen = new TreeMap<>();
    properties.forEach(
        (key, value) -> frozen.put(key.toString(), Integer.parseInt(value.toString().trim())));
    return frozen;
  }

  /**
   * Reads a source file as UTF-8.
   *
   * @param file the file
   * @return its text
   */
  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Resolves a module-relative path, tolerating a run from the repository root.
   *
   * @param relative the module-relative path
   * @return the path that exists, or the module-relative one when neither does
   */
  private static Path resolveModuleRelative(String relative) {
    Path direct = Paths.get(relative);
    if (Files.exists(direct)) {
      return direct;
    }
    Path fromRepoRoot = Paths.get("frontend").resolve(relative);
    if (Files.exists(fromRepoRoot)) {
      return fromRepoRoot;
    }
    return direct;
  }
}
