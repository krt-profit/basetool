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

package de.greluc.krt.profit.basetool.backend.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link NotificationPersonParams} against the backend sources: every render parameter a
 * notice writes is classified as a person or not, exactly once, and every listed key is still
 * written (REQ-SEC-062).
 */
class NotificationPersonParamsTest {

  /** The backend's main sources, relative to the module directory Gradle runs the tests in. */
  private static final Path MAIN_SOURCES = Path.of("src/main/java");

  /** A notice's parameter write, {@code params.put("key", …)}. */
  private static final Pattern PARAM_WRITE = Pattern.compile("params\\.put\\(\"([A-Za-z0-9_]+)\"");

  @Test
  void everyWrittenKeyIsClassifiedExactlyOnce_andEveryListedKeyIsWritten() {
    Set<String> written = writtenKeys();
    assertThat(written)
        .as("keys found in the sources (an empty scan proves nothing)")
        .hasSizeGreaterThan(40);

    Set<String> unclassified = new TreeSet<>(written);
    unclassified.removeAll(NotificationPersonParams.PERSON_KEYS);
    unclassified.removeAll(NotificationPersonParams.OTHER_KEYS);
    assertThat(unclassified)
        .as("keys a notice writes that are neither PERSON_KEYS nor OTHER_KEYS")
        .isEmpty();

    Set<String> both = new TreeSet<>(NotificationPersonParams.PERSON_KEYS);
    both.retainAll(NotificationPersonParams.OTHER_KEYS);
    assertThat(both).as("keys listed in both sets").isEmpty();

    Set<String> stale = new TreeSet<>(NotificationPersonParams.PERSON_KEYS);
    stale.addAll(NotificationPersonParams.OTHER_KEYS);
    stale.removeAll(written);
    assertThat(stale).as("listed keys no notice writes any more").isEmpty();
  }

  @Test
  void redactsOnlyWholePersonValues_ignoringCase() {
    Map<String, String> params =
        Map.of(
            "actor", "Valkyrie",
            "requester", "valkyrie",
            "mission", "Valkyrie",
            "reason", "Rückfrage bei Valkyrie",
            "member", "Valkyrie Two");

    Map<String, String> redacted = NotificationPersonParams.redact(params, "VALKYRIE", "#X#");

    assertThat(redacted)
        .containsEntry("actor", "#X#")
        .containsEntry("requester", "#X#")
        .containsEntry("mission", "Valkyrie")
        .containsEntry("reason", "Rückfrage bei Valkyrie")
        .containsEntry("member", "Valkyrie Two");
  }

  /**
   * Collects every key a {@code params.put} call writes in the backend's main sources.
   *
   * @return the keys
   */
  private static Set<String> writtenKeys() {
    Set<String> keys = new TreeSet<>();
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      files
          .filter(file -> file.toString().endsWith(".java"))
          .forEach(file -> collectKeys(file, keys));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return keys;
  }

  /**
   * Adds the keys one source file writes.
   *
   * @param file the source file
   * @param keys the keys collected so far
   */
  private static void collectKeys(Path file, Set<String> keys) {
    try {
      Matcher matcher = PARAM_WRITE.matcher(Files.readString(file, StandardCharsets.UTF_8));
      while (matcher.find()) {
        keys.add(matcher.group(1));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
