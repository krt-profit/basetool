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

package de.greluc.krt.profit.basetool.frontend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A committed, line-oriented golden file under {@code frontend/src/test/resources} that a test
 * compares its derived lines against.
 *
 * <p>Run {@code ./gradlew :frontend:test -PupdateSnapshots} to rewrite every golden file from the
 * current code instead of comparing; the build forwards the flag as the system property {@link
 * #UPDATE_PROPERTY}. Review the resulting diff like any other change.
 */
public final class GoldenFile {

  /** The test-JVM system property that switches every golden file to rewrite mode. */
  public static final String UPDATE_PROPERTY = "basetool.updateSnapshots";

  /** Not instantiable. */
  private GoldenFile() {}

  /**
   * Compares {@code actual} with the golden file, or rewrites the file when {@link
   * #UPDATE_PROPERTY} is {@code true}.
   *
   * <p>The failure names every line only in the code and every line only in the file, so a review
   * sees the intended change without opening a diff tool.
   *
   * @param resource the path below {@code src/test/resources}, e.g. {@code
   *     security/route-gate-snapshot.txt}
   * @param actual the derived lines, already in their canonical order
   * @param what a short description of the content, used in the failure message
   */
  public static void assertMatches(
      @NotNull String resource, @NotNull List<String> actual, @NotNull String what) {
    Path file = Path.of("src", "test", "resources").resolve(resource);
    if (Boolean.getBoolean(UPDATE_PROPERTY)) {
      write(file, actual);
      return;
    }
    List<String> expected = read(file);
    Set<String> added = new LinkedHashSet<>(actual);
    expected.forEach(added::remove);
    Set<String> removed = new LinkedHashSet<>(expected);
    actual.forEach(removed::remove);
    assertThat(actual)
        .withFailMessage(
            """
            The %s differs from the committed golden file %s.

            Only in the code (%d):
              %s

            Only in the golden file (%d):
              %s

            If the change is intended, rewrite the file with
              ./gradlew :frontend:test --tests '<this test>' -PupdateSnapshots
            and commit it with the change, so the reviewer sees it.\
            """,
            what,
            file,
            added.size(),
            String.join("\n  ", added),
            removed.size(),
            String.join("\n  ", removed))
        .containsExactlyElementsOf(expected);
  }

  /**
   * Reads the golden file's non-blank lines, ignoring carriage returns.
   *
   * @param file the golden file
   * @return its lines in file order
   */
  private static @NotNull @Unmodifiable List<String> read(@NotNull Path file) {
    assertThat(file)
        .withFailMessage(
            "golden file %s is missing; create it with -PupdateSnapshots", file.toAbsolutePath())
        .isRegularFile();
    try {
      List<String> lines = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String trimmed = line.replace("\r", "");
        if (!trimmed.isBlank()) {
          lines.add(trimmed);
        }
      }
      return List.copyOf(lines);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /**
   * Writes the lines with LF endings and a trailing newline.
   *
   * @param file the golden file
   * @param lines the lines to write
   */
  private static void write(@NotNull Path file, @NotNull List<String> lines) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
