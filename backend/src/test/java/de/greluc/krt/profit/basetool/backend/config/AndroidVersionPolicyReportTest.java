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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the startup report of the Android version policy's sources (REQ-API-020). */
class AndroidVersionPolicyReportTest {

  @Test
  @DisplayName("names the release default as the source when nothing is overridden")
  void namesTheReleaseDefault() {
    AndroidVersionPolicyReport report =
        new AndroidVersionPolicyReport(
            BoundProperties.bind(
                AndroidClientProperties.class,
                Map.of("release.minimum-version-code", 17, "release.latest-version-code", 17)));

    assertThat(report.anyOverridden()).isFalse();
    assertThat(report.describe())
        .contains("minimumVersionCode=17 (release default)")
        .contains("latestVersionCode=17 (release default)")
        .doesNotContain(AndroidVersionPolicyReport.SOURCE_OVERRIDE);
  }

  @Test
  @DisplayName("names the emergency override as the source of the field it replaces")
  void namesTheOverride() {
    AndroidVersionPolicyReport report =
        new AndroidVersionPolicyReport(
            BoundProperties.bind(
                AndroidClientProperties.class,
                Map.of(
                    "release.minimum-version-code",
                    17,
                    "emergency-override.minimum-version-code",
                    0)));

    assertThat(report.anyOverridden()).isTrue();
    assertThat(report.describe())
        .contains("minimumVersionCode=0 (emergency override)")
        .contains("latestVersionCode=0 (release default)");
  }

  @Test
  @DisplayName("publishes one override gauge per field, 1 only for the overridden one")
  void publishesOneGaugePerField() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new AndroidVersionPolicyReport(
            BoundProperties.bind(
                AndroidClientProperties.class,
                Map.of("emergency-override.latest-version-code", 18)))
        .bindTo(registry);

    assertThat(gauge(registry, MetricNames.FIELD_MINIMUM_VERSION_CODE)).isZero();
    assertThat(gauge(registry, MetricNames.FIELD_LATEST_VERSION_CODE)).isEqualTo(1.0d);
    assertThat(gauge(registry, MetricNames.FIELD_RELEASES_URL)).isZero();
    assertThat(registry.find(MetricNames.ANDROID_VERSION_POLICY_OVERRIDE).gauges()).hasSize(3);
    assertThat(MetricNames.ANDROID_VERSION_POLICY_OVERRIDE)
        .as("the alert rule reads basetool_android_version_policy_override")
        .isEqualTo("basetool.android.version.policy.override");
  }

  /**
   * Reads one field's override gauge.
   *
   * @param registry the registry the report bound to
   * @param field the {@code field} tag value
   * @return the gauge's value
   */
  private static double gauge(SimpleMeterRegistry registry, String field) {
    return registry
        .get(MetricNames.ANDROID_VERSION_POLICY_OVERRIDE)
        .tag(MetricNames.TAG_FIELD, field)
        .gauge()
        .value();
  }
}
