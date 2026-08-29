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

package de.greluc.krt.profit.basetool.frontend.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Asserts that the meters the Prometheus alert rules are written against actually exist.
 *
 * <p>An alert whose metric has no series never fires. It also never looks broken: the rule is in
 * the file, the dashboard panel is on the board, and the only symptom is silence — which is
 * indistinguishable from "nothing has gone wrong". REQ-OBS-014 calls that a dead alert and treats
 * it as worse than no alert, because it reads as coverage.
 *
 * <p>This test exists because three of them were found that way.
 * `resilience4j_circuitbreaker_state`, `resilience4j_bulkhead_available_concurrent_calls` and
 * `resilience4j_retry_calls_total` are named by rules in `monitoring/prometheus/alerts/apps.yml`
 * and by panels in `03-spring-apps.json`, and a read of the production Prometheus on 2026-08-29
 * returned **no series at all** for the whole `resilience4j_*` family — so the circuit-breaker,
 * bulkhead and retry alerts had never been able to fire, and the panels had always been empty.
 *
 * <p>These three are <b>gauges and a driven counter</b>, not lazily-created error counters. That
 * distinction is the whole point: a `basetool_*_errors_total` with no series means the error branch
 * has never been taken, which is good news (the #1238 baseline made exactly that reading). A gauge
 * with no series means it is not being published at all.
 */
@SpringBootTest
@ActiveProfiles("test")
class AlertedMeterPresenceTest {

  /** The OAuth2 client registry needs a live Keycloak to build; mocked like the sibling tests. */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @Autowired private MeterRegistry meterRegistry;

  /**
   * Meter names an alert rule or dashboard panel depends on, which must therefore be registered.
   *
   * <p>Add to this list whenever a rule is written against a meter this module publishes. Do not
   * add a lazily-created counter whose branch may legitimately never be taken — this list is for
   * meters whose <em>absence</em> is a defect, not for ones whose absence is good news.
   */
  private static final List<String> ALERTED_METERS =
      List.of(
          // monitoring/prometheus/alerts/apps.yml — CircuitBreakerOpen
          "resilience4j.circuitbreaker.state",
          // monitoring/prometheus/alerts/apps.yml — the bulkhead saturation rule
          "resilience4j.bulkhead.available.concurrent.calls",
          // monitoring/prometheus/alerts/apps.yml — the retry-rate rule
          "resilience4j.retry.calls");

  @Test
  @DisplayName("every meter an alert rule is written against is actually registered")
  void alertedMetersAreRegistered() {
    Set<String> registered =
        meterRegistry.getMeters().stream()
            .map(m -> m.getId().getName())
            .collect(Collectors.toSet());

    assertThat(ALERTED_METERS)
        .as(
            """
            These meters are named by a Prometheus alert rule or a dashboard panel but are not \
            registered, so the rule can never fire and the panel is permanently empty — a dead \
            alert reads as coverage (REQ-OBS-014). Either publish the meter or retire the rule; \
            leaving both is the one option that is not available.\
            """)
        .allSatisfy(name -> assertThat(registered).contains(name));
  }

  /**
   * The registry is not empty and does carry this module's own meters — without this the assertion
   * above could pass vacuously if the whole metrics layer failed to start.
   */
  @Test
  @DisplayName("the registry is populated, so the check above cannot pass vacuously")
  void theRegistryIsPopulated() {
    assertThat(meterRegistry.getMeters()).hasSizeGreaterThan(20);
  }
}
