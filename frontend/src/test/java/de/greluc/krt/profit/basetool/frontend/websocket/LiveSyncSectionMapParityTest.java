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

package de.greluc.krt.profit.basetool.frontend.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Build-time enforcement of the REQ-FE-015 three-mirror-points rule for live-sync section maps: the
 * server-side {@link LiveSyncTopicClass} whitelist and the page's JS seam map must name exactly the
 * same section keys. A key added on one side without the other silently strands peers stale
 * (REQ-FE-010) — this test turns that drift into a red build.
 *
 * <p>Reads the shipped JS module from the classpath ({@code src/main/resources} is on the test
 * runtime classpath), extracts the seam-map keys, and asserts set-equality with the registry.
 */
class LiveSyncSectionMapParityTest {

  /**
   * Matches one entry of a seam map — a plain, single- or double-quoted key followed by {@code
   * : { container}. Anchoring on {@code container} avoids matching unrelated nested objects.
   */
  private static final Pattern SEAM_ENTRY =
      Pattern.compile("(?:'([\\w-]+)'|\"([\\w-]+)\"|([\\w-]+))\\s*:\\s*\\{\\s*container");

  @Test
  void missionSeamMap_matchesTheMissionTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/mission-detail.js", "MISSION_SECTIONS");
    assertThat(jsKeys)
        .as("MISSION_SECTIONS keys in mission-detail.js vs LiveSyncTopicClass.MISSION whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.MISSION.allowedSections());
  }

  @Test
  void operationSeamMap_matchesTheOperationTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/operation-detail.js", "OPERATION_SECTIONS");
    assertThat(jsKeys)
        .as(
            "OPERATION_SECTIONS keys in operation-detail.js vs LiveSyncTopicClass.OPERATION"
                + " whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.OPERATION.allowedSections());
  }

  @Test
  void ordersQueueSeamMap_matchesTheOrdersQueueTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/orders-index.js", "ORDERS_SECTIONS");
    assertThat(jsKeys)
        .as("ORDERS_SECTIONS keys in orders-index.js vs LiveSyncTopicClass.ORDERS_QUEUE whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.ORDERS_QUEUE.allowedSections());
  }

  /**
   * Extracts the top-level keys of a JS object literal assigned to {@code variableName} in the
   * given classpath resource.
   *
   * @param resource the classpath path of the JS module (e.g. {@code /static/js/mission-detail.js})
   * @param variableName the seam-map variable (e.g. {@code MISSION_SECTIONS})
   * @return the seam-map section keys
   * @throws IOException if the resource cannot be read
   */
  private static Set<String> seamMapKeys(String resource, String variableName) throws IOException {
    String js = readResource(resource);
    String objectLiteral = extractObjectLiteral(js, variableName);
    Set<String> keys = new LinkedHashSet<>();
    Matcher matcher = SEAM_ENTRY.matcher(objectLiteral);
    while (matcher.find()) {
      String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
      if (key == null) {
        key = matcher.group(3);
      }
      keys.add(key);
    }
    assertThat(keys).as("no seam-map keys found for %s in %s", variableName, resource).isNotEmpty();
    return keys;
  }

  /**
   * Returns the brace-balanced object-literal body assigned to {@code variableName} (excluding the
   * outer braces), so nested objects do not terminate the scan early.
   *
   * @param js the full module source
   * @param variableName the seam-map variable name
   * @return the object-literal body between its outer braces
   */
  private static String extractObjectLiteral(String js, String variableName) {
    int nameAt = js.indexOf(variableName);
    assertThat(nameAt).as("%s declaration present", variableName).isNotNegative();
    int open = js.indexOf('{', nameAt);
    assertThat(open).as("%s object-literal opening brace", variableName).isNotNegative();
    int depth = 0;
    for (int i = open; i < js.length(); i++) {
      char c = js.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return js.substring(open + 1, i);
        }
      }
    }
    throw new AssertionError("Unbalanced braces for " + variableName);
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream in = LiveSyncSectionMapParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
