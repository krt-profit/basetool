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

package de.greluc.krt.profit.basetool.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.testsupport.profile.TestProfileScan;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * No backend test repeats the {@code test} profile Gradle already activates, so contexts that
 * differ only by that annotation are shared (REQ-OPS-039).
 */
class TestProfileConventionTest {

  private static final Path TESTS = Path.of("src/test/java");

  @Test
  void noTestRepeatsTheTestProfile() {
    assertThat(TestProfileScan.javaSourceCount(TESTS))
        .as("the scan must see the backend's test sources")
        .isGreaterThanOrEqualTo(682);
    assertThat(TestProfileScan.redundantTestProfiles(TESTS))
        .as(
            "@ActiveProfiles(\"test\") is redundant: every Test task sets"
                + " spring.profiles.active=test, and the annotation splits the test-context cache."
                + " Remove it")
        .isEmpty();
  }
}
