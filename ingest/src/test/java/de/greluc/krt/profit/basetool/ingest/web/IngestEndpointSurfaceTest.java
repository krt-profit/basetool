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

package de.greluc.krt.profit.basetool.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes;
import de.greluc.krt.profit.basetool.ingest.edge.IngestPathScope;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.testsupport.web.Call;
import de.greluc.krt.profit.basetool.testsupport.web.EndpointEnumeration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the gateway's routed surface to exactly the exchange routes (REQ-INGEST-001, REQ-XCH-001),
 * since the protective filters scope themselves to {@code /exchange/**} via {@link
 * IngestPathScope}. Uses {@link EndpointEnumeration}; only Boot's {@code /error} is tolerated
 * besides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class IngestEndpointSurfaceTest {

  /** The complete routed gateway surface (REQ-INGEST-001). */
  private static final Set<Call> INGEST_SURFACE =
      Set.of(
          new Call(HttpMethod.GET, "/exchange/v1/openapi.json"),
          new Call(HttpMethod.GET, "/exchange/v1/schemas/x"),
          new Call(HttpMethod.GET, "/exchange/v1"),
          new Call(HttpMethod.POST, "/exchange/v1/me/installation"),
          new Call(HttpMethod.POST, "/exchange/v1/me/account-check"),
          new Call(HttpMethod.POST, "/exchange/v1/catalog/resolve"),
          new Call(HttpMethod.GET, "/exchange/v1/catalog/locations"),
          new Call(HttpMethod.GET, "/exchange/v1/me/blueprints"),
          new Call(HttpMethod.GET, "/exchange/v1/me/stock"),
          new Call(HttpMethod.GET, "/exchange/v1/me/ships"),
          new Call(HttpMethod.GET, "/exchange/v1/me/org-demand"),
          new Call(HttpMethod.POST, "/exchange/v1/me/blueprints/changes"),
          new Call(HttpMethod.POST, "/exchange/v1/me/stock/changes"),
          new Call(HttpMethod.POST, "/exchange/v1/me/ships/changes"),
          new Call(HttpMethod.POST, "/exchange/v1/me/drafts/blueprints"),
          new Call(HttpMethod.POST, "/exchange/v1/me/drafts/refinery-orders"));

  /**
   * Boot's {@code BasicErrorController}: the target of the container's error dispatch, not an
   * application endpoint. Whether the dispatcher lists it depends on which auto-configurations the
   * shared test context happened to start first, so it is tolerated rather than required.
   */
  private static final String ERROR_PATH = "/error";

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;

  @Test
  void theDispatcherRoutesExactlyTheGatewaySurface() {
    List<Call> routed =
        EndpointEnumeration.mappings(context).stream()
            .filter(call -> !call.path().equals(ERROR_PATH))
            .toList();

    assertThat(routed)
        .as(
            "every endpoint outside /exchange is served WITHOUT the payload cap, rate"
                + " limit and access log — add it there or extend IngestPathScope first")
        .containsExactlyInAnyOrderElementsOf(INGEST_SURFACE);
  }

  @Test
  void everyRoutedIngestEndpointIsInsideTheProtectiveFilterScope() {
    for (Call call : INGEST_SURFACE) {
      MockHttpServletRequest request =
          new MockHttpServletRequest(call.method().name(), call.path());
      request.setRequestURI(call.path());

      assertThat(IngestPathScope.isExchangeRequest(request))
          .as("%s must be covered by the protective filters", call)
          .isTrue();
    }
  }

  @Test
  void everyServedExchangeRouteIsInTheGatedRouteTable() {
    for (Call call : EndpointEnumeration.mappings(context)) {
      if (!EndpointEnumeration.isUnder(call.path(), "/exchange")
          || call.path().equals("/exchange/v1/openapi.json")
          || call.path().startsWith("/exchange/v1/schemas/")) {
        continue;
      }
      assertThat(ExchangeRoutes.find(call.method().name(), call.path()))
          .as("%s is served but not in ExchangeRoutes, so the gate would answer 404", call)
          .isPresent();
    }
  }

  @Test
  void theRemovedExtractorRoutesAreNotRouted() {
    assertThat(EndpointEnumeration.mappings(context))
        .noneMatch(call -> EndpointEnumeration.isUnder(call.path(), "/v1"));
  }

  @Test
  void theGatewayServesNoGeneratedApiDocument() {
    assertThat(EndpointEnumeration.mappings(context))
        .noneMatch(call -> EndpointEnumeration.isUnder(call.path(), "/v3/api-docs"));
  }

  @Test
  void theEnumerationIsNotVacuous() {
    assertThat(EndpointEnumeration.patterns(context, HttpMethod.POST))
        .contains("/exchange/v1/me/drafts/refinery-orders", "/exchange/v1/me/drafts/blueprints");
    assertThat(EndpointEnumeration.patterns(context, HttpMethod.GET))
        .contains("/exchange/v1/openapi.json", "/exchange/v1/schemas/{name}");
  }
}
