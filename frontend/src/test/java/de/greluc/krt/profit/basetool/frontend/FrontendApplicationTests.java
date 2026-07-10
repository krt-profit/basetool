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

package de.greluc.krt.profit.basetool.frontend;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.web.ProxyingHandlerMethodArgumentResolver;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/** Boots the full frontend context and asserts app-wide wiring invariants. */
@SpringBootTest
class FrontendApplicationTests {

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @Autowired private RequestMappingHandlerAdapter requestMappingHandlerAdapter;

  /** The application context bootstraps cleanly with the transitive auto-configs excluded. */
  @Test
  void contextLoads() {}

  /**
   * REQ-OBS-015 (#1202): {@link FrontendApplication} excludes Spring Data's {@code
   * DataWebAutoConfiguration} because this module does no Spring Data web binding, so its {@link
   * ProxyingHandlerMethodArgumentResolver} must be absent from the MVC resolver chain. That
   * resolver is what logged the "not annotated with @ProjectedPayload" WARN for {@code
   * SquadronContextAdvice}'s interface-typed {@code @ModelAttribute} catalogue parameters; with it
   * gone the false positive can no longer be raised. Asserting on the live resolver list (rather
   * than the exclude annotation) proves the exclusion actually took effect end-to-end.
   */
  @Test
  void springDataWebProxyingResolverIsNotRegistered() {
    var resolvers = requestMappingHandlerAdapter.getArgumentResolvers();

    assertNotNull(resolvers, "the initialised adapter always exposes its resolver list");
    assertTrue(
        resolvers.stream().noneMatch(ProxyingHandlerMethodArgumentResolver.class::isInstance),
        "ProxyingHandlerMethodArgumentResolver must stay out of the MVC resolver chain so it cannot"
            + " emit the @ProjectedPayload false-positive WARN (REQ-OBS-015, #1202)");
  }
}
