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

package de.greluc.krt.profit.basetool.ingest.assembly;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Tests that production never starts without the DPoP {@code htu} origin (REQ-INGEST-012). */
class PublicBaseUrlGuardTest {

  private static MockEnvironment environment(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @Test
  void prodWithoutThePublicOriginRefusesToStart() {
    PublicBaseUrlGuard guard = new PublicBaseUrlGuard(environment("prod"), TestProperties.ingest());

    assertThatThrownBy(guard::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IRI_INGEST_PUBLIC_BASE_URL");
  }

  @Test
  void prodWithABlankPublicOriginRefusesToStart() {
    PublicBaseUrlGuard guard =
        new PublicBaseUrlGuard(environment("prod"), TestProperties.ingest("public-base-url", "  "));

    assertThatThrownBy(guard::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void prodWithThePublicOriginStarts() {
    PublicBaseUrlGuard guard =
        new PublicBaseUrlGuard(
            environment("prod"),
            TestProperties.ingest("public-base-url", "https://ingest.example.org"));

    assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
  }

  @Test
  void devAndTestKeepTheRequestDerivedOrigin() {
    assertThatCode(
            () ->
                new PublicBaseUrlGuard(environment("dev"), TestProperties.ingest())
                    .afterPropertiesSet())
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                new PublicBaseUrlGuard(environment("test"), TestProperties.ingest())
                    .afterPropertiesSet())
        .doesNotThrowAnyException();
  }
}
