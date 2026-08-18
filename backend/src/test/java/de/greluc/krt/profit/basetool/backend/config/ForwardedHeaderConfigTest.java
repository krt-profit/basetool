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

import de.greluc.krt.profit.basetool.backend.filter.ClientIpContextFilter;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.autoconfigure.servlet.ForwardedHeaderFilterCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Locks the ordering invariant the whole client-IP attribution rests on (REQ-SEC-011, ADR-0090
 * pattern): {@link ForwardedHeaderFilter} MUST run strictly after {@link ClientIpContextFilter}, so
 * the latter still sees the raw peer and the unconsumed {@code X-Forwarded-For} chain.
 *
 * <p>The assertions deliberately read the <b>registration</b> order rather than any {@code Ordered}
 * implementation on the filters. {@code ServletContextInitializerBeans} sorts {@code
 * RegistrationBean}s by their own order and never consults the wrapped filter, so a filter-level
 * order would be the value a reader trusts and the container ignores — and a test asserting it
 * would stay green while {@code registration.setOrder(HIGHEST_PRECEDENCE + 5)} silently restored
 * the original bug. This mirrors the frontend test of the same name, which asserts the filter order
 * because there the filter is a {@code @Component} and that order is the effective one.
 */
class ForwardedHeaderConfigTest {

  private final ForwardedHeaderConfig config = new ForwardedHeaderConfig();

  /** A provider that offers no customizer, mirroring a context where no bean is defined. */
  private static final ObjectProvider<ForwardedHeaderFilterCustomizer> NO_CUSTOMIZER =
      new ObjectProvider<>() {
        @Override
        public ForwardedHeaderFilterCustomizer getObject() {
          throw new UnsupportedOperationException("no customizer bean in this context");
        }

        @Override
        public ForwardedHeaderFilterCustomizer getObject(Object... args) {
          throw new UnsupportedOperationException("no customizer bean in this context");
        }

        @Override
        public ForwardedHeaderFilterCustomizer getIfAvailable() {
          return null;
        }

        @Override
        public ForwardedHeaderFilterCustomizer getIfUnique() {
          return null;
        }
      };

  /**
   * Builds the client-IP registration the way the application context does.
   *
   * @return the registration under test.
   */
  private FilterRegistrationBean<ClientIpContextFilter> clientIpRegistration() {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setTrustedProxies(List.of("10.0.0.0/24"));
    return config.clientIpContextFilter(properties);
  }

  @Test
  void forwardedHeaderFilterRunsExactlyOneSlotAfterClientIpResolution() {
    int resolverOrder = clientIpRegistration().getOrder();
    int forwardedOrder = config.forwardedHeaderFilter(NO_CUSTOMIZER).getOrder();

    assertThat(resolverOrder)
        .as("client-IP resolution must be at the very highest precedence")
        .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(forwardedOrder)
        .as(
            "ForwardedHeaderFilter must run strictly after it, or the resolver reads a rewritten "
                + "request and the trusted-proxy walk becomes dead code again")
        .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1)
        .isGreaterThan(resolverOrder);
  }

  @Test
  void bothRegistrationsCoverTheWholeUriSpaceAndTheSameDispatcherTypes() {
    // A mismatch here would leave a dispatch on which one filter runs and the other does not —
    // exactly the split that makes attribution silently inconsistent rather than broken.
    assertThat(clientIpRegistration().getUrlPatterns()).containsExactly("/*");
    assertThat(config.forwardedHeaderFilter(NO_CUSTOMIZER).getFilter())
        .as("mirrors Boot's own registration, so scheme/host rewriting is unchanged")
        .isInstanceOf(ForwardedHeaderFilter.class);
  }

  @Test
  void theCustomizerHookBootAppliesIsHonoured() {
    // Boot's own registration applies a ForwardedHeaderFilterCustomizer before wrapping. None ships
    // in Boot 4.1, so dropping it breaks nothing today and silently disables a documented extension
    // point on this module alone tomorrow.
    boolean[] applied = {false};
    ObjectProvider<ForwardedHeaderFilterCustomizer> provider =
        new ObjectProvider<>() {
          @Override
          public ForwardedHeaderFilterCustomizer getObject() {
            return filter -> applied[0] = true;
          }

          @Override
          public ForwardedHeaderFilterCustomizer getObject(Object... args) {
            return getObject();
          }

          @Override
          public ForwardedHeaderFilterCustomizer getIfAvailable() {
            return getObject();
          }

          @Override
          public ForwardedHeaderFilterCustomizer getIfUnique() {
            return getObject();
          }
        };

    config.forwardedHeaderFilter(provider);

    assertThat(applied[0])
        .as("a customizer bean must reach the hand-rolled registration as it reaches Boot's")
        .isTrue();
  }
}
