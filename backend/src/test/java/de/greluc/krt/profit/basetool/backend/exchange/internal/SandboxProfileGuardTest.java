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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Unit tests for {@link SandboxProfileGuard}. */
class SandboxProfileGuardTest {

  @TempDir Path dir;

  @Test
  void aSandboxImageRefusesTheProdProfile() throws IOException {
    Path marker = Files.createFile(dir.resolve("SANDBOX"));
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    assertThatThrownBy(() -> new SandboxProfileGuard(marker).check(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sandbox image");
  }

  @Test
  void aSandboxImageRunsWithTheDevProfile() throws IOException {
    Path marker = Files.createFile(dir.resolve("SANDBOX"));
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");

    assertThatCode(() -> new SandboxProfileGuard(marker).check(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void anImageWithoutTheMarkerRunsWithTheProdProfile() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    assertThatCode(() -> new SandboxProfileGuard(dir.resolve("SANDBOX")).check(environment))
        .doesNotThrowAnyException();
  }
}
