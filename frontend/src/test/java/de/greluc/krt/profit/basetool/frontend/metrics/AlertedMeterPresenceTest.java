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
 * Asserts that the meters the Prometheus alert rules reference actually exist, since an alert on a
 * metric without series never fires (REQ-OBS-014). Covers gauges and driven counters such as the
 * {@code resilience4j_*} family, whose absence means they are not published at all.
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
          "resilience4j.circuitbreaker.state",
          "resilience4j.bulkhead.available.concurrent.calls",
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
