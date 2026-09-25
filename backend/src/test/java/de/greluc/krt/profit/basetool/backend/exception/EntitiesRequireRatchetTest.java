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

package de.greluc.krt.profit.basetool.backend.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The ratchet behind REQ-API-004's fetch-or-throw rule (BE-SIMP-01): a service-layer lookup raises
 * its 404 through {@link Entities#require}, never through a hand-written {@code
 * optional.orElseThrow(() -> new NotFoundException(…))}.
 *
 * <p>The 312 hand-written sites were migrated on 2026-09-23, message by message, so the ceiling is
 * zero — 287 that threw {@code NotFoundException} and 25 that threw JPA's {@code
 * jakarta.persistence.EntityNotFoundException}, which {@code GlobalExceptionHandler.handleNotFound}
 * answers with the identical 404 problem. It may only ever go down: a new hand-written site fails
 * the build here and names its file and line. {@link Entities} itself is the one place the idiom is
 * allowed — it is the implementation.
 *
 * <p>A source scan rather than an ArchUnit rule on purpose: the compiled form of the lambda is an
 * anonymous synthetic method, which ArchUnit cannot tell apart from any other {@code
 * NotFoundException} construction.
 */
class EntitiesRequireRatchetTest {

  /**
   * How many hand-written sites may remain. Zero since the migration; lower it, never raise it — a
   * site that genuinely cannot use {@link Entities#require} belongs in a reviewed change to this
   * number with its reason, not in a silent increase.
   */
  private static final int CEILING = 0;

  /**
   * The idiom, tolerant of the line breaks google-java-format puts into a long chain, of a
   * fully-qualified name — 37 sites were written that way and only surfaced once their names were
   * shortened to imports — and of JPA's {@code EntityNotFoundException}, which answers the same
   * 404.
   */
  private static final Pattern HAND_WRITTEN =
      Pattern.compile(
          "\\.\\s*orElseThrow\\(\\s*\\(\\)\\s*->\\s*new\\s+(?:[\\w.]+\\.)?(?:Entity)?NotFoundException\\(");

  @Test
  void noServiceLookupHandWritesTheNotFoundIdiom() throws IOException {
    Path sources = findBackendMainSources();
    List<String> sites = new ArrayList<>();
    int requireCalls = 0;
    try (Stream<Path> files = Files.walk(sources)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        if (file.getFileName().toString().equals("Entities.java")) {
          continue;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        requireCalls += text.split("Entities\\.require\\(", -1).length - 1;
        Matcher matcher = HAND_WRITTEN.matcher(text);
        while (matcher.find()) {
          sites.add(sources.relativize(file) + ":" + lineOf(text, matcher.start()));
        }
      }
    }

    assertThat(requireCalls).as("Entities.require call sites seen by the scan").isGreaterThan(200);
    assertThat(sites)
        .as(
            "Hand-written orElseThrow(() -> new NotFoundException(...)) sites; use"
                + " Entities.require(optional, message) instead (REQ-API-004)")
        .hasSizeLessThanOrEqualTo(CEILING);
  }

  @Test
  void theScanRecognisesBothLayoutsOfTheIdiom() {
    assertThat(
            HAND_WRITTEN.matcher(
                "repo.findById(id).orElseThrow(() -> new NotFoundException(\"x\"))"))
        .matches(Matcher::find);
    assertThat(
            HAND_WRITTEN.matcher(
                "repo\n"
                    + "    .findById(id)\n"
                    + "    .orElseThrow(\n"
                    + "        () -> new NotFoundException(m))"))
        .matches(Matcher::find);
    assertThat(
            HAND_WRITTEN.matcher(
                "repo.findById(id).orElseThrow(() -> new"
                    + " de.greluc.krt.profit.basetool.backend.exception.NotFoundException(m))"))
        .matches(Matcher::find);
    assertThat(HAND_WRITTEN.matcher("Entities.require(repo.findById(id), \"x\")"))
        .matches(m -> !m.find());
    assertThat(
            HAND_WRITTEN.matcher(
                "o.orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(\"x\"))"))
        .matches(Matcher::find);
    assertThat(HAND_WRITTEN.matcher("o.orElseThrow(() -> new BadRequestException(\"x\"))"))
        .matches(m -> !m.find());
  }

  private static int lineOf(String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /**
   * Walks up from the test's working directory to the repository root ({@code settings.gradle.kts})
   * and returns the backend's main source root, so the scan works whichever directory the test task
   * runs in.
   *
   * @return {@code backend/src/main/java} of this checkout
   */
  private static Path findBackendMainSources() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException(
          "Could not locate the repository root (settings.gradle.kts) from "
              + System.getProperty("user.dir"));
    }
    Path sources = dir.resolve("backend/src/main/java");
    assertThat(sources).isDirectory();
    return sources;
  }
}
