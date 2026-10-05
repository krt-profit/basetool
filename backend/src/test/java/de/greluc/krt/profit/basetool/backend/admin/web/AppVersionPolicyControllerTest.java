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

package de.greluc.krt.profit.basetool.backend.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.admin.internal.AndroidClientProperties;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the served-version policy of the forced-update gate (REQ-API-010, REQ-API-020), chiefly
 * that an unconfigured server answers "no floor" and that the override takes precedence.
 */
class AppVersionPolicyControllerTest {

  @Test
  @DisplayName("an unconfigured server states no floor, rather than locking everyone out")
  void unconfiguredServerStatesNoFloor() {
    AppVersionPolicyDto policy = policyOf(BoundProperties.defaults(AndroidClientProperties.class));

    assertThat(policy.minimumVersionCode()).isZero();
    assertThat(policy.latestVersionCode()).isZero();
    assertThat(policy.releasesUrl()).contains("basetool-android/releases");
  }

  @Test
  @DisplayName("the two version numbers stay apart, so a release is not a wall")
  void configuredPolicyKeepsFloorAndLatestApart() {
    AndroidClientProperties properties =
        BoundProperties.bind(
            AndroidClientProperties.class,
            Map.of("release.minimum-version-code", 7, "release.latest-version-code", 11));

    AppVersionPolicyDto policy = policyOf(properties);

    assertThat(policy.minimumVersionCode()).isEqualTo(7);
    assertThat(policy.latestVersionCode()).isEqualTo(11);
  }

  @Test
  @DisplayName("the answer carries the emergency override where one is set")
  void answersTheEmergencyOverride() {
    AndroidClientProperties properties =
        BoundProperties.bind(
            AndroidClientProperties.class,
            Map.of(
                "release.minimum-version-code", 7,
                "release.latest-version-code", 11,
                "emergency-override.minimum-version-code", 9,
                "emergency-override.releases-url", "https://example.org/releases"));

    AppVersionPolicyDto policy = policyOf(properties);

    assertThat(policy.minimumVersionCode()).isEqualTo(9);
    assertThat(policy.latestVersionCode()).isEqualTo(11);
    assertThat(policy.releasesUrl()).isEqualTo("https://example.org/releases");
  }

  /**
   * Reads the policy the controller would answer for the given configuration.
   *
   * @param properties the configured policy.
   * @return the response body.
   */
  private AppVersionPolicyDto policyOf(AndroidClientProperties properties) {
    return new AppVersionPolicyController(properties).versionPolicy().getBody();
  }
}
