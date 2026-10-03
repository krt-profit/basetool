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

package de.greluc.krt.profit.basetool.testsupport.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Finds test classes that repeat the {@code test} profile the build already activates for every
 * test JVM (REQ-OPS-039).
 *
 * <p>An {@code @ActiveProfiles("test")} is part of Spring's test-context cache key, so it splits
 * contexts that are otherwise identical. An {@code @ActiveProfiles} naming any other profile, alone
 * or beside {@code test}, is not redundant and is not reported.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestProfileScan {

  private static final Pattern REDUNDANT =
      Pattern.compile(
          "@(?:org\\.springframework\\.test\\.context\\.)?ActiveProfiles\\s*\\(\\s*"
              + "(?:(?:value|profiles)\\s*=\\s*)?(?:\\{\\s*)?\"test\"\\s*,?\\s*}?\\s*\\)");

  /**
   * Reports every redundant {@code @ActiveProfiles("test")} in the Java sources below the roots.
   *
   * @param roots source roots, walked recursively; a missing root is skipped
   * @return one {@code path:line} entry per annotation, the path relative to its root
   */
  public static @NotNull @Unmodifiable List<String> redundantTestProfiles(@NotNull Path... roots) {
    List<String> hits = new ArrayList<>();
    for (Path root : roots) {
      for (Path file : javaSources(root)) {
        String source = read(file);
        Matcher matcher = REDUNDANT.matcher(source);
        while (matcher.find()) {
          hits.add(
              root.relativize(file).toString().replace('\\', '/')
                  + ":"
                  + lineOf(source, matcher.start()));
        }
      }
    }
    return List.copyOf(hits);
  }

  /**
   * Counts the Java sources below the roots, the selection a scan covers.
   *
   * @param roots source roots, walked recursively; a missing root is skipped
   * @return the number of {@code .java} files
   */
  public static int javaSourceCount(@NotNull Path... roots) {
    int count = 0;
    for (Path root : roots) {
      count += javaSources(root).size();
    }
    return count;
  }

  /**
   * Lists the Java sources below one root.
   *
   * @param root a source root
   * @return the {@code .java} files, sorted, or none when the root does not exist
   */
  private static List<Path> javaSources(@NotNull Path root) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads one source as UTF-8.
   *
   * @param file the source
   * @return its text
   */
  private static String read(@NotNull Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns the 1-based line of an offset.
   *
   * @param text the text
   * @param offset an index into it
   * @return the line number
   */
  private static int lineOf(@NotNull String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }
}
