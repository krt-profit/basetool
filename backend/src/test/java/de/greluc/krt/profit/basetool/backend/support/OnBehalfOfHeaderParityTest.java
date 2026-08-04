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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the on-behalf-of header literal across the module boundary (ADR-0129).
 *
 * <p>The ingest gateway and the backend are separate Gradle modules with no shared code, so the
 * header name is declared twice. A rename on one side alone does not fail anything: the gateway
 * would send a header the backend never reads, the backend would fall back to the token's own
 * subject — which is the gateway's service account — and <em>every ingest upload would silently be
 * attributed to a service account instead of to the member who sent it</em>. Nothing would error
 * and nothing would be logged.
 *
 * <p>Reads the gateway's source rather than its class, because the backend test runtime does not
 * have the ingest module on its classpath.
 */
class OnBehalfOfHeaderParityTest {

  /**
   * The gateway's declaration, matched on the constant rather than on any occurrence of the text.
   */
  private static final Pattern GATEWAY_DECLARATION =
      Pattern.compile("ON_BEHALF_OF_HEADER\\s*=\\s*\"([^\"]+)\"");

  @Test
  void theGatewaySendsExactlyTheHeaderTheBackendReads() throws IOException {
    Path gatewaySource =
        findRepoRoot()
            .resolve(
                "ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/service/"
                    + "BackendImportClient.java");
    assertThat(gatewaySource).as("the gateway's relay client must exist").exists();

    String source = Files.readString(gatewaySource, StandardCharsets.UTF_8);
    Matcher matcher = GATEWAY_DECLARATION.matcher(source);
    assertThat(matcher.find())
        .as("BackendImportClient must still declare ON_BEHALF_OF_HEADER")
        .isTrue();

    assertThat(matcher.group(1))
        .as("a rename on one side alone attributes every ingest write to the service account")
        .isEqualTo(ActingMemberHeader.ON_BEHALF_OF_HEADER);
  }

  /**
   * Walks up from the test's working directory until the directory holding {@code
   * settings.gradle.kts} is found, so the cross-module source path resolves regardless of which
   * module's directory the test task runs in.
   *
   * @return the repository root directory
   * @throws IllegalStateException if no {@code settings.gradle.kts} is found on the way up
   */
  private static Path findRepoRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException(
          "Could not locate the repository root (settings.gradle.kts) from "
              + System.getProperty("user.dir"));
    }
    return dir;
  }
}
