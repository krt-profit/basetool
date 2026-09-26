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

package de.greluc.krt.profit.basetool.backend.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contract for {@link StartupBannerListener#sanitiseJdbcUrl(String)}: supported URL variants never
 * echo credentials, and null input is safe.
 */
class StartupBannerListenerTest {

  @Test
  void null_ShouldReturnUnknown() {
    assertThat(StartupBannerListener.sanitiseJdbcUrl(null)).isEqualTo("unknown");
    assertThat(StartupBannerListener.sanitiseJdbcUrl("")).isEqualTo("unknown");
    assertThat(StartupBannerListener.sanitiseJdbcUrl("   ")).isEqualTo("unknown");
  }

  @Test
  void cleanUrl_ShouldBeReturnedUnchanged() {
    String url = "jdbc:postgresql://db:5432/krt_basetool";
    assertThat(StartupBannerListener.sanitiseJdbcUrl(url)).isEqualTo(url);
  }

  @Test
  void passwordInQueryString_ShouldBeMasked() {
    String url = "jdbc:postgresql://db:5432/krt?user=krt_user&password=s3cret";
    String sanitised = StartupBannerListener.sanitiseJdbcUrl(url);
    assertThat(sanitised).doesNotContain("s3cret", "krt_user");
    assertThat(sanitised).contains("password=***", "user=***");
  }

  @Test
  void inlineCredentials_ShouldBeMasked() {
    String url = "jdbc:postgresql://krt_user:s3cret@db:5432/krt";
    String sanitised = StartupBannerListener.sanitiseJdbcUrl(url);
    assertThat(sanitised).doesNotContain("s3cret", "krt_user:");
    assertThat(sanitised).contains("***@db:5432");
  }
}
