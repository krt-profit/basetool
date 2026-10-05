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

package de.greluc.krt.profit.basetool.backend.admin.internal;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reports where each value of the Android version policy comes from (REQ-API-020): one log line at
 * startup and the {@link MetricNames#ANDROID_VERSION_POLICY_OVERRIDE} gauge per field.
 *
 * <p>The line is {@code WARN} while an emergency override is in force, {@code INFO} otherwise.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AndroidVersionPolicyReport implements MeterBinder {

  /** Source label of a value committed with the release. */
  static final String SOURCE_RELEASE = "release default";

  /** Source label of a value taken from the host's emergency override. */
  static final String SOURCE_OVERRIDE = "emergency override";

  /** The bound policy. */
  private final AndroidClientProperties properties;

  /**
   * Registers one gauge per policy field, {@code 1} while the override replaces it.
   *
   * @param registry the registry the gauges are bound to
   */
  @Override
  public void bindTo(@NotNull MeterRegistry registry) {
    gauge(
        registry,
        MetricNames.FIELD_MINIMUM_VERSION_CODE,
        properties.minimumVersionCodeOverridden());
    gauge(
        registry, MetricNames.FIELD_LATEST_VERSION_CODE, properties.latestVersionCodeOverridden());
    gauge(registry, MetricNames.FIELD_RELEASES_URL, properties.releasesUrlOverridden());
  }

  /** Logs the policy in force and the source of each value once the application is ready. */
  @EventListener(ApplicationReadyEvent.class)
  public void logPolicy() {
    String line = describe();
    if (anyOverridden()) {
      log.warn("{} — emergency override in force; fold it into the release default", line);
    } else {
      log.info("{}", line);
    }
  }

  /**
   * Describes the policy in force with the source of every value.
   *
   * @return the description, for example {@code Android version policy: minimumVersionCode=17
   *     (release default), …}
   */
  @NotNull
  String describe() {
    return "Android version policy: minimumVersionCode="
        + properties.minimumVersionCode()
        + " ("
        + source(properties.minimumVersionCodeOverridden())
        + "), latestVersionCode="
        + properties.latestVersionCode()
        + " ("
        + source(properties.latestVersionCodeOverridden())
        + "), releasesUrl="
        + properties.releasesUrl()
        + " ("
        + source(properties.releasesUrlOverridden())
        + ")";
  }

  /**
   * Tells whether any field comes from the emergency override.
   *
   * @return {@code true} when at least one override is in force
   */
  boolean anyOverridden() {
    return properties.minimumVersionCodeOverridden()
        || properties.latestVersionCodeOverridden()
        || properties.releasesUrlOverridden();
  }

  private static String source(boolean overridden) {
    return overridden ? SOURCE_OVERRIDE : SOURCE_RELEASE;
  }

  private static void gauge(MeterRegistry registry, String field, boolean overridden) {
    double value = overridden ? 1.0d : 0.0d;
    Gauge.builder(MetricNames.ANDROID_VERSION_POLICY_OVERRIDE, () -> value)
        .description("1 while the emergency override replaces the release default of this field.")
        .tag(MetricNames.TAG_FIELD, field)
        .register(registry);
  }
}
