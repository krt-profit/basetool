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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ImageNameSubstitutor;

/**
 * Verifies that the backend's test classpath activates the PostgreSQL pin, so the {@code
 * jdbc:tc:postgresql:18-alpine} URL starts the production image by digest (TST-18).
 */
class PinnedPostgresImageTest {

  /**
   * Testcontainers' configured substitutor turns the JDBC URL's tagged image into the pinned one.
   */
  @Test
  void theJdbcUrlsImageIsSubstitutedWithThePinnedDigest() {
    DockerImageName declared = DockerImageName.parse("postgres").withTag("18-alpine");

    assertThat(ImageNameSubstitutor.instance().apply(declared).asCanonicalNameString())
        .isEqualTo(TestImages.POSTGRES);
  }
}
