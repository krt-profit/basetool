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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Client attribution through the <strong>real filter chain</strong> (A8, REQ-OBS-018).
 *
 * <p>{@link ApiClientMetricsFilterTest} covers what the filter decides; this covers where it sits,
 * which is the half a unit test cannot see and the half that has already broken once. Registering
 * the filter with {@code addFilterBefore(…, ActingMemberFilter.class)} <em>above</em> the call that
 * introduces {@code ActingMemberFilter} fails the whole context with "does not have a registered
 * order" — 813 tests, one cause — and the position it guards is not cosmetic: one slot later, an
 * on-behalf-of call would already have had its authentication replaced by an {@code
 * ActingMemberAuthentication} that carries no claims, and every gateway request would be counted as
 * an anonymous one.
 */
@SpringBootTest
class ApiClientMetricsChainTest {

  @Autowired private WebApplicationContext context;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private FilterChainProxy filterChainProxy;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * The security-chain post-processor, imported by method reference to keep the static import list
   * readable.
   *
   * @return the MockMvc configurer that installs the real {@link FilterChainProxy}
   */
  private static org.springframework.test.web.servlet.setup.MockMvcConfigurer springSecurity() {
    return org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
        .springSecurity();
  }

  /**
   * Reads the attribution counter for one client label.
   *
   * <p>A delta, not an absolute: the registry is a context-scoped singleton and every test that ran
   * before this one in the same context has already contributed.
   *
   * @param clientId the bounded {@code client_id} label
   * @return the current count, or {@code 0} when nothing has been counted under it
   */
  private double requests(String clientId) {
    Counter counter =
        meterRegistry
            .find(MetricNames.API_CLIENT_REQUESTS)
            .tag(MetricNames.TAG_CLIENT_ID, clientId)
            .counter();
    return counter == null ? 0d : counter.count();
  }

  @Test
  @DisplayName("the attribution filter runs before the identity swap, in the chain as built")
  void theAttributionFilterRunsBeforeTheActingMemberSwap() {
    // The proxy holds several chains — the management-port and monitoring-scrape ones come first
    // by @Order — so the API chain has to be found by content rather than by position.
    List<Filter> filters =
        filterChainProxy.getFilterChains().stream()
            .map(chain -> chain.getFilters())
            .filter(chain -> indexOf(chain, ActingMemberFilter.class) >= 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no chain contains ActingMemberFilter"));
    int attribution = indexOf(filters, ApiClientMetricsFilter.class);
    int identitySwap = indexOf(filters, ActingMemberFilter.class);

    assertThat(attribution)
        .as("ApiClientMetricsFilter must be in the chain at all")
        .isNotNegative();
    assertThat(attribution)
        .as(
            "after the swap, a gateway call carries an ActingMemberAuthentication with no claims "
                + "and would be counted as anonymous")
        .isLessThan(identitySwap);
  }

  @Test
  void aRequestThroughTheRealChainIsAttributedToItsClient() throws Exception {
    double before = requests("basetool-android");

    mockMvc.perform(
        get("/api/v1/missions").with(jwt().jwt(token -> token.claim("azp", "basetool-android"))));

    assertThat(requests("basetool-android") - before).isEqualTo(1.0d);
  }

  @Test
  void aClientTheDeploymentDoesNotKnowIsCountedUnderTheBoundedLiteral() throws Exception {
    double before = requests(MetricNames.CLIENT_ID_OTHER);

    mockMvc.perform(
        get("/api/v1/missions").with(jwt().jwt(token -> token.claim("azp", "curl-by-hand"))));

    assertThat(requests(MetricNames.CLIENT_ID_OTHER) - before).isEqualTo(1.0d);
    assertThat(
            meterRegistry
                .find(MetricNames.API_CLIENT_REQUESTS)
                .tag(MetricNames.TAG_CLIENT_ID, "curl-by-hand")
                .counter())
        .as("the azp must never reach the label, not even through the real chain")
        .isNull();
  }

  /**
   * Finds a filter's position in the chain by type.
   *
   * @param filters the chain as built
   * @param type the filter class to locate
   * @return the index, or {@code -1} when the chain does not contain it
   */
  private static int indexOf(List<Filter> filters, Class<? extends Filter> type) {
    for (int i = 0; i < filters.size(); i++) {
      if (type.isInstance(filters.get(i))) {
        return i;
      }
    }
    return -1;
  }
}
