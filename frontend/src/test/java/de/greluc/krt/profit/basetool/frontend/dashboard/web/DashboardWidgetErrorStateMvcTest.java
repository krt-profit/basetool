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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.DashboardCatalogueFixture;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Every dashboard widget endpoint that reads the backend answers a backend error or refusal with a
 * non-2xx status, which {@code dashboard-widgets.js} turns into the widget's error state with a
 * retry button (REQ-UI-034); none renders an empty body as if nothing qualified.
 */
@SpringBootTest
class DashboardWidgetErrorStateMvcTest {

  private static final String BASE = "/dashboard/widgets/";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    DashboardCatalogueFixture.stub(backendApiClient);
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  static Stream<Arguments> backendReadingWidgets() {
    return Stream.of(
            "upcoming-days",
            "information",
            "next-mission",
            "hangar-readiness",
            "orders-to-process",
            "material-demand",
            "refinery-ready",
            "market",
            "supply-needs",
            "bank-requests",
            "assigned-orders",
            "low-stock",
            "bank",
            "deletion-requests",
            "discord-registrations",
            "open-terms",
            "sync-status")
        .flatMap(path -> Stream.of(Arguments.of(path, 403), Arguments.of(path, 500)));
  }

  @ParameterizedTest(name = "{0} answers a backend {1} with an error status")
  @MethodSource("backendReadingWidgets")
  void aBackendFailureIsNotRenderedAsAnEmptyWidget(String widget, int backendStatus)
      throws Exception {
    BackendServiceException failure =
        new BackendServiceException("backend refused", null, backendStatus);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenThrow(failure);
    when(backendApiClient.get(anyString(), anyClass())).thenThrow(failure);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenThrow(failure);
    when(backendApiClient.get(anyString(), anyClass(), any(Object[].class))).thenThrow(failure);

    mockMvc
        .perform(
            get(BASE + widget)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .locale(Locale.GERMAN))
        .andExpect(result -> {
          int code = result.getResponse().getStatus();
          if (code < 400) {
            throw new AssertionError(widget + " answered " + code + " on a backend error");
          }
        });
  }
}
