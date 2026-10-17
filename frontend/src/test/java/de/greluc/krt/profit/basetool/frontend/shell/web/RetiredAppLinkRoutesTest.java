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

package de.greluc.krt.profit.basetool.frontend.shell.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Pins the removal of the Android App Link surface (ADR-0268): no handler serves its three paths,
 * so they answer 404, and none of them is reachable without a session.
 */
@SpringBootTest
@DisplayName("Retired App Link routes")
class RetiredAppLinkRoutesTest {

  private static final List<String> RETIRED_PATHS =
      List.of("/.well-known/assetlinks.json", "/app/callback", "/app/link-help");

  @Autowired private WebApplicationContext context;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  /** Keeps the real client registration, which performs OIDC discovery, out of the test context. */
  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @Test
  @DisplayName("no controller method is mapped to a retired path")
  void noHandlerIsMappedToARetiredPath() {
    List<String> patterns =
        handlerMapping.getHandlerMethods().keySet().stream()
            .flatMap(info -> info.getPatternValues().stream())
            .toList();

    assertThat(patterns).doesNotContainAnyElementsOf(RETIRED_PATHS);
  }

  @ParameterizedTest
  @DisplayName("an anonymous request to a retired path is sent to the login, not served")
  @FieldSource("RETIRED_PATHS")
  void anAnonymousRequestIsSentToTheLogin(String path) throws Exception {
    MockHttpServletResponse response =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build()
            .perform(get(path))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).as(path).isEqualTo(302);
    assertThat(response.getRedirectedUrl()).as(path).contains("/oauth2/authorization/keycloak");
  }
}
