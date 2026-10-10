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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Pins the {@code uri} tag caps of {@code http.server.requests} and {@code http.client.requests}
 * above the route count (REQ-OBS-006).
 */
@SpringBootTest
class UriTagCapacityTest {

  /** Tags beside the route templates: {@code UNKNOWN}, {@code NOT_FOUND}, {@code REDIRECTION}, … */
  private static final int HEADROOM = 50;

  /** Selection floor: the distinct route templates when the guard was introduced. */
  private static final int MIN_ROUTE_TEMPLATES = 415;

  /** Floor of the client cap: the backend's route templates plus headroom, which it relays to. */
  private static final int MIN_CLIENT_CAP = 1000;

  @Autowired private WebApplicationContext context;
  @Autowired private MetricsProperties metricsProperties;

  /** Mocked so the context starts without a backend; nothing here issues a request. */
  @MockitoBean
  private de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient backendApiClient;

  /** The frontend is an OAuth2 client; the registry is what the security chain wires through. */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @Test
  void serverCapCoversEveryRouteTemplate() {
    RequestMappingHandlerMapping handlerMapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    Set<String> templates = new TreeSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      templates.addAll(info.getPatternValues());
    }
    int cap = metricsProperties.getWeb().getServer().getMaxUriTags();

    assertThat(templates)
        .as("selection floor: the dispatcher must report at least today's route templates")
        .hasSizeGreaterThanOrEqualTo(MIN_ROUTE_TEMPLATES);
    assertThat(templates.size() + HEADROOM)
        .as(
            "management.metrics.web.server.max-uri-tags (%d) must exceed the %d route templates"
                + " plus %d fixed tags, or every route first hit after the cap goes unmetered",
            cap, templates.size(), HEADROOM)
        .isLessThanOrEqualTo(cap);
  }

  @Test
  void clientCapCoversTheBackendRoutes() {
    assertThat(metricsProperties.getWeb().getClient().getMaxUriTags())
        .as(
            "management.metrics.web.client.max-uri-tags must cover every backend route the"
                + " frontend relays to, or every call first made after the cap goes unmetered")
        .isGreaterThanOrEqualTo(MIN_CLIENT_CAP);
  }
}
