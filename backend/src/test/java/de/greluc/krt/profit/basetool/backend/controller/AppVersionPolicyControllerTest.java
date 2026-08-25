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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.config.AndroidClientProperties;
import de.greluc.krt.profit.basetool.backend.model.dto.AppVersionPolicyDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The served-version policy the forced-update gate keys on (REQ-API-010).
 *
 * <p>The endpoint has no branches worth mocking a web layer for — its anonymity is asserted where
 * anonymity is decided, in {@code ApiVhostAnonymousSurfaceTest}, and its response shape is frozen
 * in {@code ExternalContractTest}. What is left, and what actually decides whether members can use
 * the app, are the property defaults: a server nobody has configured must answer "no floor".
 */
class AppVersionPolicyControllerTest {

  @Test
  @DisplayName("an unconfigured server states no floor, rather than locking everyone out")
  void unconfiguredServerStatesNoFloor() {
    AppVersionPolicyDto policy = policyOf(new AndroidClientProperties());

    // The whole point of the default. A non-zero floor here would mean that the first deployment
    // carrying this code refuses every installed build, and the members it refuses are exactly the
    // ones who cannot be told why by any other channel.
    assertThat(policy.minimumVersionCode()).isZero();
    assertThat(policy.latestVersionCode()).isZero();
    assertThat(policy.releasesUrl()).contains("basetool-android/releases");
  }

  @Test
  @DisplayName("the two version numbers stay apart, so a release is not a wall")
  void configuredPolicyKeepsFloorAndLatestApart() {
    AndroidClientProperties properties = new AndroidClientProperties();
    properties.setMinimumVersionCode(7);
    properties.setLatestVersionCode(11);

    AppVersionPolicyDto policy = policyOf(properties);

    // A client on 9 is behind the newest build and above the floor: it must be offered an update,
    // never blocked. Collapsing these two into one number is the mistake this pins.
    assertThat(policy.minimumVersionCode()).isEqualTo(7);
    assertThat(policy.latestVersionCode()).isEqualTo(11);
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
