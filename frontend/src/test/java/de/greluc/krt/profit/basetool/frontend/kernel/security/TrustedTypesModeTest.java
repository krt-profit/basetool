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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests for the Trusted Types switch (ADR-0239): the parser, the header writer in both modes and
 * the mode gauge.
 */
class TrustedTypesModeTest {

  private static final String CSP = "Content-Security-Policy";

  @Test
  void aMissingOrUnknownValueIsReport() {
    for (String raw : new String[] {null, "", "  ", "bogus", "block"}) {
      assertThat(TrustedTypesMode.parse(raw))
          .as(String.valueOf(raw))
          .isEqualTo(TrustedTypesMode.REPORT);
    }
  }

  @Test
  void theModeIsParsedWhateverItsCase() {
    assertThat(TrustedTypesMode.parse(" Enforce ")).isEqualTo(TrustedTypesMode.ENFORCE);
    assertThat(TrustedTypesMode.parse("REPORT")).isEqualTo(TrustedTypesMode.REPORT);
  }

  @Test
  void reportModeSendsTheDirectivesInAReportOnlyPolicyAndLeavesTheEnforcedOneAlone() {
    MockHttpServletResponse response = written(TrustedTypesMode.REPORT);

    assertThat(response.getHeader(SecurityHeaders.REPORT_ONLY_HEADER))
        .isEqualTo("require-trusted-types-for 'script'; trusted-types krt-html krt-fragment");
    assertThat(response.getHeader(CSP))
        .contains("script-src 'nonce-n0nce' 'strict-dynamic'")
        .doesNotContain("trusted-types");
  }

  @Test
  void enforceModeAddsTheDirectivesToTheEnforcedPolicyAndSendsNoReportOnlyPolicy() {
    MockHttpServletResponse response = written(TrustedTypesMode.ENFORCE);

    assertThat(response.getHeader(SecurityHeaders.REPORT_ONLY_HEADER)).isNull();
    assertThat(response.getHeader(CSP))
        .contains("script-src 'nonce-n0nce' 'strict-dynamic'")
        .endsWith("; require-trusted-types-for 'script'; trusted-types krt-html krt-fragment");
  }

  @Test
  void noModeAllowsADefaultPolicyOrDuplicates() {
    assertThat(SecurityHeaders.TRUSTED_TYPES_DIRECTIVES)
        .doesNotContain("default")
        .doesNotContain("'allow-duplicates'")
        .doesNotContain("*");
  }

  @Test
  void theGaugeNamesTheEffectiveMode() {
    SimpleMeterRegistry enforce = bound("enforce");
    assertThat(gauge(enforce, "enforce")).isEqualTo(1.0);
    assertThat(gauge(enforce, "report")).isZero();

    SimpleMeterRegistry fallback = bound(null);
    assertThat(gauge(fallback, "report")).isEqualTo(1.0);
    assertThat(gauge(fallback, "enforce")).isZero();
    assertThat(fallback.find(MetricNames.TRUSTED_TYPES_MODE).gauges())
        .hasSize(TrustedTypesMode.values().length);
  }

  private static MockHttpServletResponse written(TrustedTypesMode mode) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CspNonceFilter.REQUEST_ATTRIBUTE, "n0nce");
    MockHttpServletResponse response = new MockHttpServletResponse();
    SecurityHeaders.cspNonceHeaderWriter("https://auth.example.test/realms/iri", mode)
        .writeHeaders(request, response);
    return response;
  }

  private static SimpleMeterRegistry bound(String raw) {
    MockEnvironment environment = new MockEnvironment();
    if (raw != null) {
      environment.setProperty(TrustedTypesMode.PROPERTY, raw);
    }
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new TrustedTypesModeMetric(environment).bindTo(registry);
    return registry;
  }

  private static double gauge(SimpleMeterRegistry registry, String mode) {
    return registry
        .get(MetricNames.TRUSTED_TYPES_MODE)
        .tag(MetricNames.TAG_MODE, mode)
        .gauge()
        .value();
  }
}
