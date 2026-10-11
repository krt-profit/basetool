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

import de.greluc.krt.profit.basetool.frontend.support.GoldenFile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Pins the frontend's route table with its effective gates and requires a gate on every handler
 * (REQ-FE-025).
 *
 * <p>The snapshot is {@code src/test/resources/security/route-gate-snapshot.txt}, one line per
 * mapping of the dispatcher's {@code requestMappingHandlerMapping} as {@link RouteGateSnapshot}
 * renders it. A package move must leave it byte-identical; an intended route or gate change
 * rewrites it with {@code ./gradlew :frontend:test --tests '*RouteGateSnapshotTest'
 * -PupdateSnapshots} and shows up in review as a diff of that file.
 */
@SpringBootTest
class RouteGateSnapshotTest {

  /** Golden file of the route table, below {@code src/test/resources}. */
  private static final String SNAPSHOT = "security/route-gate-snapshot.txt";

  /** Selection floor: the dispatcher's mapping count when the snapshot was introduced. */
  private static final int MIN_MAPPINGS = 538;

  /** Selection floor: the application handler count when the rule was introduced. */
  private static final int MIN_APPLICATION_HANDLERS = 536;

  /**
   * The application handlers allowed to carry no {@code @PreAuthorize}, named exactly as {@code
   * SimpleName#method}: the anonymous surface of REQ-SEC-052, which must answer without a session.
   */
  static final Set<String> PUBLIC_HANDLERS =
      Set.of(
          "AppLinkController#callback",
          "AppLinkController#linkHelp",
          "AssetLinksController#assetLinks",
          "HomeController#home",
          "ImpressumController#showImpressum",
          "OssLicensesController#showLicenses",
          "PrivacyController#showPrivacy",
          "TermsController#showTerms",
          "WebAppManifestController#manifest");

  @Autowired private WebApplicationContext context;

  /** Mocked so the context starts without a backend; nothing here issues a request. */
  @MockitoBean
  private de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient backendApiClient;

  /** The frontend is an OAuth2 client; the registry is what the security chain wires through. */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @Test
  void theRouteTableMatchesTheCommittedSnapshot() {
    List<String> lines = RouteGateSnapshot.lines(mappings());

    assertThat(lines)
        .as("selection floor: the dispatcher must report at least today's mappings")
        .hasSizeGreaterThanOrEqualTo(MIN_MAPPINGS);
    GoldenFile.assertMatches(SNAPSHOT, lines, "frontend route/gate table");
  }

  @Test
  void everyApplicationHandlerCarriesAnEffectiveGate() {
    List<HandlerMethod> handlers = RouteGateSnapshot.applicationHandlers(mappings().values());

    assertThat(handlers)
        .as("selection floor: the rule must see at least today's application handlers")
        .hasSizeGreaterThanOrEqualTo(MIN_APPLICATION_HANDLERS);
    assertThat(RouteGateSnapshot.ungatedHandlers(handlers, PUBLIC_HANDLERS))
        .as(
            "every handler needs a @PreAuthorize of its own or on its class; a handler moved out"
                + " of a class-gated controller would otherwise be served to any signed-in member")
        .isEmpty();
  }

  @Test
  void thePublicHandlerAllowListNamesOnlyUngatedHandlersThatExist() {
    Set<String> ungated = new TreeSet<>();
    for (HandlerMethod handler : RouteGateSnapshot.applicationHandlers(mappings().values())) {
      if (RouteGateSnapshot.NO_GATE.equals(
          RouteGateSnapshot.effectiveGate(handler.getBeanType(), handler.getMethod()))) {
        ungated.add(RouteGateSnapshot.handlerName(handler));
      }
    }

    assertThat(ungated)
        .as("an allow-list entry that names a gated or a removed handler is stale; remove it")
        .containsAll(PUBLIC_HANDLERS);
  }

  /**
   * The application dispatcher's mappings.
   *
   * @return every mapping of the {@code requestMappingHandlerMapping} bean
   */
  private Map<RequestMappingInfo, HandlerMethod> mappings() {
    return context
        .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
        .getHandlerMethods();
  }
}
