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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

/**
 * Unit tests for {@link AppVersionAdvice}: a real version, a blank version and a missing {@code
 * BuildProperties} bean must each yield a non-{@code null} string.
 */
class AppVersionAdviceTest {

  @Test
  void appVersion_withBuildPropertiesPresent_returnsVersionString() {
    Properties props = new Properties();
    props.setProperty("version", "1.2.3");
    BuildProperties buildProperties = new BuildProperties(props);
    AppVersionAdvice advice = new AppVersionAdvice(Optional.of(buildProperties));

    String version = advice.appVersion();

    assertEquals("1.2.3", version, "Should return the version from BuildProperties");
  }

  @Test
  void appVersion_withBuildPropertiesAbsent_returnsFallback() {
    AppVersionAdvice advice = new AppVersionAdvice(Optional.empty());

    String version = advice.appVersion();

    assertEquals(
        AppVersionAdvice.FALLBACK_VERSION,
        version,
        "Should fall back to the dev marker when no bean is wired");
  }

  @Test
  void appVersion_withBlankVersion_returnsFallback() {
    Properties props = new Properties();
    props.setProperty("version", "   ");
    BuildProperties buildProperties = new BuildProperties(props);
    AppVersionAdvice advice = new AppVersionAdvice(Optional.of(buildProperties));

    String version = advice.appVersion();

    assertEquals(
        AppVersionAdvice.FALLBACK_VERSION,
        version,
        "A blank version field must not surface as an empty chip in the sidebar");
  }
}
