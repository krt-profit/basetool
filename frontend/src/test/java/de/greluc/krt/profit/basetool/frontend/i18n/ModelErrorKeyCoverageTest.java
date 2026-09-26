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

package de.greluc.krt.profit.basetool.frontend.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests that every literal message key a controller puts into the model or flash under an {@code
 * *error} attribute exists in all three bundles.
 *
 * <p>Only literals shaped like a message key are checked.
 */
class ModelErrorKeyCoverageTest {

  /** {@code model.addAttribute("error", "some.message.key")}, and its flash sibling. */
  private static final Pattern ERROR_ATTRIBUTE =
      Pattern.compile(
          "add(?:Flash)?Attribute\\(\\s*\"(\\w*[eE]rror)\"\\s*,\\s*\"([a-z][\\w.]*\\.[\\w.]+)\"");

  /** The three bundles, by the name a failure should name. */
  private static final Map<String, Path> BUNDLES =
      Map.of(
          "messages.properties",
          resolveModuleRelative("src/main/resources/messages.properties"),
          "messages_de.properties",
          resolveModuleRelative("src/main/resources/messages_de.properties"),
          "messages_en.properties",
          resolveModuleRelative("src/main/resources/messages_en.properties"));

  @Test
  void everyErrorKeyPutInTheModelExistsInEveryBundle() throws IOException {
    Map<String, List<String>> keysBySource = errorKeys();
    assertThat(keysBySource)
        .as("the scan found no error keys at all, so it is no longer looking at anything")
        .isNotEmpty();

    Map<String, String> bundleContents = new LinkedHashMap<>();
    for (Map.Entry<String, Path> bundle : BUNDLES.entrySet()) {
      bundleContents.put(
          bundle.getKey(), Files.readString(bundle.getValue(), StandardCharsets.UTF_8));
    }

    List<String> missing = new ArrayList<>();
    keysBySource.forEach(
        (key, sources) ->
            bundleContents.forEach(
                (bundleName, content) -> {
                  if (!content.contains("\n" + key + "=") && !content.startsWith(key + "=")) {
                    missing.add(key + " (" + String.join(", ", sources) + ") -> " + bundleName);
                  }
                }));

    assertThat(missing)
        .as(
            "Each of these renders as ??key_de?? in a red box on the page named in brackets. Add"
                + " the key to the bundle, area-scoped like every other page's load error"
                + " (admin.audit.error.load, notifications.error.load, …) -- umlauts as \\\\uXXXX.")
        .isEmpty();
  }

  /**
   * Every message-key literal handed to the model under an error attribute, with the files it came
   * from.
   *
   * @return key to the source file names that pass it
   * @throws IOException when the source tree cannot be walked
   */
  private static Map<String, List<String>> errorKeys() throws IOException {
    Path root = resolveModuleRelative("src/main/java");
    assertThat(Files.isDirectory(root))
        .as("source root not found at " + root.toAbsolutePath())
        .isTrue();

    Map<String, List<String>> out = new LinkedHashMap<>();
    try (Stream<Path> tree = Files.walk(root)) {
      tree.filter(p -> p.toString().endsWith(".java"))
          .sorted()
          .forEach(
              source -> {
                Matcher m = ERROR_ATTRIBUTE.matcher(read(source));
                while (m.find()) {
                  out.computeIfAbsent(m.group(2), k -> new ArrayList<>())
                      .add(source.getFileName().toString());
                }
              });
    }
    return out;
  }

  /**
   * Reads a source file, turning the checked exception into an unchecked one for stream use.
   *
   * @param source the file
   * @return its content
   */
  private static String read(Path source) {
    try {
      return Files.readString(source, StandardCharsets.UTF_8);
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
