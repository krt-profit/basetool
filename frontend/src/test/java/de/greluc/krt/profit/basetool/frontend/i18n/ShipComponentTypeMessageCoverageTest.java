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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests that every {@code ShipComponentType} and {@code ShipComponentClass} the backend can send
 * has an {@code inventory.items.componentType.*} / {@code inventory.items.itemClass.*} label in all
 * three bundles (REQ-INV-061, REQ-INV-062).
 *
 * <p>The enums are read from the backend's source files, since the frontend holds no backend
 * classes.
 */
class ShipComponentTypeMessageCoverageTest {

  /** The backend package holding both enums. */
  private static final String CATALOGUE_API =
      "../backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/api/";

  /** The three bundles, by the name a failure should name. */
  private static final Map<String, Path> BUNDLES =
      Map.of(
          "messages.properties",
          resolveModuleRelative("src/main/resources/messages.properties"),
          "messages_de.properties",
          resolveModuleRelative("src/main/resources/messages_de.properties"),
          "messages_en.properties",
          resolveModuleRelative("src/main/resources/messages_en.properties"));

  /** An enum constant: an upper-snake identifier opening its constructor arguments. */
  private static final Pattern CONSTANT =
      Pattern.compile("^\\s{2}([A-Z][A-Z0-9_]*)\\(", Pattern.MULTILINE);

  @ParameterizedTest
  @CsvSource({
    "ShipComponentType, inventory.items.componentType.",
    "ShipComponentClass, inventory.items.itemClass."
  })
  void everyConstantHasALabelInEveryBundle(String enumName, String keyPrefix) throws IOException {
    Set<String> constants = constantsOf(enumName);
    assertThat(constants).as("parsed %s constants", enumName).hasSizeGreaterThanOrEqualTo(5);

    Map<String, List<String>> missingByBundle = new LinkedHashMap<>();
    for (Map.Entry<String, Path> bundle : BUNDLES.entrySet()) {
      String content = Files.readString(bundle.getValue(), StandardCharsets.UTF_8);
      List<String> missing = new ArrayList<>();
      for (String constant : constants) {
        if (!content.contains(keyPrefix + constant + "=")) {
          missing.add(constant);
        }
      }
      if (!missing.isEmpty()) {
        missingByBundle.put(bundle.getKey(), missing);
      }
    }

    assertThat(missingByBundle)
        .as(
            "A component type without a label falls back to the category chip, a class without one"
                + " shows no class chip. Add "
                + keyPrefix
                + "<CONSTANT> to the bundle named here -- umlauts as \\\\uXXXX in the .properties"
                + " files.")
        .isEmpty();
  }

  /**
   * An enum's constants, read from the backend source.
   *
   * @param enumName the simple name of an enum in the catalogue API package
   * @return the constant names in declaration order
   * @throws IOException when the source file cannot be read
   */
  private static Set<String> constantsOf(String enumName) throws IOException {
    Path file = resolveModuleRelative(CATALOGUE_API + enumName + ".java");
    assertThat(Files.isRegularFile(file))
        .as(enumName + ".java not found at " + file.toAbsolutePath())
        .isTrue();
    String source = Files.readString(file, StandardCharsets.UTF_8);
    int body = source.indexOf("public enum " + enumName);
    assertThat(body).as("the enum declaration").isGreaterThan(0);

    Set<String> out = new LinkedHashSet<>();
    Matcher m = CONSTANT.matcher(source.substring(body));
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  /**
   * Resolves a path given relative to the frontend module root, tolerating a run from the
   * repository root.
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
