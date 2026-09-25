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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests that every remote combobox source registered on {@code window.krtComboboxRemoteSources} has
 * a {@code krtComboboxI18n.kinds} entry in {@code fragments/head.html}, and vice versa (REQ-FE-011,
 * REQ-FE-016).
 */
class ComboboxKindsParityTest {

  /** The three registry modules that assign {@code krtComboboxRemoteSources['<marker>'] = …}. */
  private static final List<String> REGISTRY_MODULES =
      List.of(
          "/static/js/krt-user-search.js",
          "/static/js/krt-catalog-search.js",
          "/static/js/krt-bank-account-search.js");

  /**
   * Matches a remote-source registration: a bracketed single-quoted marker assigned on the shared
   * registry object. Anchoring on the assignment ({@code ] =}) skips reads and comments.
   */
  private static final Pattern REGISTRATION =
      Pattern.compile("krtComboboxRemoteSources\\[\\s*'([\\w-]+)'\\s*\\]\\s*=");

  /**
   * Matches one {@code kinds} entry in head.html — a quoted marker key opening an object whose
   * first property is {@code placeholder}. Anchoring on {@code placeholder} avoids matching
   * unrelated quoted-key objects elsewhere in the fragment.
   */
  private static final Pattern KINDS_ENTRY = Pattern.compile("'([\\w-]+)':\\s*\\{\\s*placeholder");

  /**
   * Asserts set equality between the markers of the three JS registry modules and the {@code
   * krtComboboxI18n.kinds} keys.
   *
   * @throws IOException if a classpath resource cannot be read
   */
  @Test
  void kindsMap_matchesTheRegisteredRemoteSources() throws IOException {
    Set<String> registered = new LinkedHashSet<>();
    for (String module : REGISTRY_MODULES) {
      Matcher matcher = REGISTRATION.matcher(readResource(module));
      while (matcher.find()) {
        registered.add(matcher.group(1));
      }
    }
    assertThat(registered)
        .as(
            "remote-source registrations found in %s (guards a path/idiom rename)",
            REGISTRY_MODULES)
        .isNotEmpty();

    Set<String> kinds = new LinkedHashSet<>();
    Matcher matcher = KINDS_ENTRY.matcher(readResource("/templates/fragments/head.html"));
    while (matcher.find()) {
      kinds.add(matcher.group(1));
    }

    assertThat(kinds)
        .as("krtComboboxI18n.kinds keys in fragments/head.html vs registered remote sources")
        .containsExactlyInAnyOrderElementsOf(registered);
  }

  /**
   * Reads a classpath resource as UTF-8 text, failing the test when it is missing.
   *
   * @param resource the absolute classpath resource path
   * @return the resource content
   * @throws IOException if the resource stream cannot be read
   */
  private static String readResource(String resource) throws IOException {
    try (InputStream in = ComboboxKindsParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
