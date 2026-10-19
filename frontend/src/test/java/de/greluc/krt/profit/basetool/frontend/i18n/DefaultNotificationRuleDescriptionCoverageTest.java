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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests that every notification rule a Flyway migration seeds has a user-facing description in all
 * three bundles, and that none of them carries a requirement id (REQ-NOTIF-007).
 *
 * <p>The seeds are read from the backend's migration sources, since the frontend holds no backend
 * resources.
 */
class DefaultNotificationRuleDescriptionCoverageTest {

  /** The key prefix the rules page resolves a seeded rule's description under. */
  private static final String KEY_PREFIX = "admin.notificationRules.defaultDescription.";

  /** The backend's Flyway migrations. */
  private static final Path MIGRATIONS =
      resolveModuleRelative("../backend/src/main/resources/db/migration");

  /** The three bundles, by the name a failure should name. */
  private static final Map<String, Path> BUNDLES =
      Map.of(
          "messages.properties",
          resolveModuleRelative("src/main/resources/messages.properties"),
          "messages_de.properties",
          resolveModuleRelative("src/main/resources/messages_de.properties"),
          "messages_en.properties",
          resolveModuleRelative("src/main/resources/messages_en.properties"));

  /** A seeded {@code notification_rule} row: its id, two codes, then a "Default:" description. */
  private static final Pattern SEEDED_RULE =
      Pattern.compile(
          "\\(\\s*'([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})'\\s*,"
              + "\\s*'[A-Z0-9_]+'\\s*,\\s*'[A-Z0-9_]+'\\s*,\\s*'Default:");

  @Test
  void everySeededRuleHasADescriptionInEveryBundle() throws IOException {
    Set<String> ids = seededRuleIds();
    assertThat(ids)
        .as("seeded rule ids parsed from " + MIGRATIONS.toAbsolutePath())
        .hasSizeGreaterThan(70);

    Map<String, List<String>> missingByBundle = new LinkedHashMap<>();
    for (Map.Entry<String, Path> bundle : BUNDLES.entrySet()) {
      Properties messages = load(bundle.getValue());
      List<String> missing = new ArrayList<>();
      for (String id : ids) {
        String text = messages.getProperty(KEY_PREFIX + id);
        if (text == null || text.isBlank()) {
          missing.add(id);
        }
      }
      if (!missing.isEmpty()) {
        missingByBundle.put(bundle.getKey(), missing);
      }
    }

    assertThat(missingByBundle)
        .as(
            "These seeded notification rules would show their English seed text on the admin"
                + " rules page. Add "
                + KEY_PREFIX
                + "<rule id> to the bundle named here -- umlauts as \\\\uXXXX.")
        .isEmpty();
  }

  @Test
  void noDefaultDescriptionCarriesARequirementId() throws IOException {
    for (Map.Entry<String, Path> bundle : BUNDLES.entrySet()) {
      Properties messages = load(bundle.getValue());
      for (String key : messages.stringPropertyNames()) {
        if (key.startsWith(KEY_PREFIX)) {
          assertThat(messages.getProperty(key))
              .as(bundle.getKey() + " " + key)
              .doesNotContain("REQ-")
              .doesNotStartWith("Default:");
        }
      }
    }
  }

  /**
   * The ids of every rule a migration seeds with a "Default:" description.
   *
   * @return the rule ids, sorted
   * @throws IOException when the migration folder cannot be read
   */
  private static Set<String> seededRuleIds() throws IOException {
    assertThat(Files.isDirectory(MIGRATIONS))
        .as("migration folder not found at " + MIGRATIONS.toAbsolutePath())
        .isTrue();
    Set<String> ids = new TreeSet<>();
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".sql")).toList()) {
        Matcher m = SEEDED_RULE.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (m.find()) {
          ids.add(m.group(1));
        }
      }
    }
    return ids;
  }

  /**
   * Loads one bundle with the {@code .properties} escapes resolved.
   *
   * @param bundle the bundle file
   * @return its messages
   * @throws IOException when the bundle cannot be read
   */
  private static Properties load(Path bundle) throws IOException {
    Properties messages = new Properties();
    try (Reader reader = Files.newBufferedReader(bundle, StandardCharsets.ISO_8859_1)) {
      messages.load(reader);
    }
    return messages;
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
