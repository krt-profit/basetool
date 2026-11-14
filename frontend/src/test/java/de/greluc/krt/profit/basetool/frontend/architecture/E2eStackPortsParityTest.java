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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code E2eStackExtension.STACK_PORTS} names every host port the dev-profile
 * services of {@code docker-compose.yml} publish, so the pre-flight check of an E2E or preview
 * stack (REQ-OPS-057) finds every port a second stack would collide on, reading both files as text.
 */
class E2eStackPortsParityTest {

  /** The base compose file, relative to the {@code frontend} module the test runs in. */
  private static final Path COMPOSE = Path.of("..", "docker-compose.yml");

  /** The extension that checks the ports before {@code docker compose up}. */
  private static final Path STACK_EXTENSION =
      Path.of("src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/E2eStackExtension.java");

  /** A loopback-bound port mapping, the form every dev-profile service publishes. */
  private static final Pattern LOOPBACK_PORT = Pattern.compile("\"127\\.0\\.0\\.1:(\\d+):\\d+\"");

  /** The {@code STACK_PORTS = List.of(...)} declaration, its numbers in group 1. */
  private static final Pattern STACK_PORTS =
      Pattern.compile("STACK_PORTS\\s*=\\s*List\\.of\\(([\\d,\\s]+)\\)");

  /**
   * The extension checks exactly the ports the dev profile publishes.
   *
   * @throws IOException if one of the files cannot be read
   */
  @Test
  void theExtensionChecksEveryPortTheDevProfilePublishes() throws IOException {
    Matcher compose = LOOPBACK_PORT.matcher(Files.readString(COMPOSE, StandardCharsets.UTF_8));
    List<Integer> published = new ArrayList<>();
    while (compose.find()) {
      published.add(Integer.valueOf(compose.group(1)));
    }
    Matcher declared =
        STACK_PORTS.matcher(Files.readString(STACK_EXTENSION, StandardCharsets.UTF_8));
    assertThat(declared.find()).as("E2eStackExtension declares STACK_PORTS").isTrue();
    List<Integer> checked =
        Arrays.stream(declared.group(1).split(","))
            .map(String::strip)
            .map(Integer::valueOf)
            .toList();

    assertThat(published).as("docker-compose.yml publishes the dev-profile ports").isNotEmpty();
    assertThat(checked)
        .as("STACK_PORTS must name every loopback port docker-compose.yml publishes")
        .containsExactlyInAnyOrderElementsOf(published);
  }
}
