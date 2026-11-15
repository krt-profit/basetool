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

package de.greluc.krt.profit.basetool.frontend.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.web.TermsController;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.SessionGateRefusalException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutMiscAdvice;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.OrgUnitContextAdvice;
import de.greluc.krt.profit.basetool.frontend.kernel.model.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGateFilter;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGates;
import de.greluc.krt.profit.basetool.frontend.kernel.web.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.frontend.navigation.client.NavigationBackendClient;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPersonalisation;
import de.greluc.krt.profit.basetool.frontend.navigation.web.NavigationAdvice;
import de.greluc.krt.profit.basetool.frontend.navigation.web.NavigationPreferencesLoader;
import de.greluc.krt.profit.basetool.frontend.navigation.web.NavigationPreferencesReader;
import de.greluc.krt.profit.basetool.frontend.navigation.web.NavigationRegistry;
import de.greluc.krt.profit.basetool.frontend.navigation.web.NavigationTabBarResolver;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationStatusDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.web.HomeLocationGate;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.web.PurchasingFeatureState;
import de.greluc.krt.profit.basetool.frontend.purchasing.web.PurchasingNavigationAdvice;
import de.greluc.krt.profit.basetool.frontend.purchasing.web.PurchasingRequestState;
import de.greluc.krt.profit.basetool.frontend.shell.web.ImpressumController;
import de.greluc.krt.profit.basetool.frontend.shell.web.OssLicenseCatalog;
import de.greluc.krt.profit.basetool.frontend.shell.web.OssLicensesController;
import de.greluc.krt.profit.basetool.frontend.shell.web.PrivacyController;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.web.SupplyFeatureState;
import de.greluc.krt.profit.basetool.frontend.supply.web.SupplyNavigationAdvice;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.hierarchicalroles.NullRoleHierarchy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The pages a session gate leaves open, the legal pages and the open-source licences, render for a
 * member the gate holds whatever the layout chrome reads (REQ-SEC-028, REQ-UI-021, ADR-0385): with
 * every chrome read and the page's own read refused by the gate, and the purchasing switch's cache
 * cold, each page answers {@code 200} with the chrome degraded instead of redirecting to the gate,
 * and the layout marks it so the page's scripts keep the browser on it.
 */
class GateExemptPageChromeMvcTest {

  /** The backend read behind the layout context, the capability flags and the badge. */
  private static final String LAYOUT_PATH = "/api/v1/me/layout";

  private final PatchChangeBackendClient patchChangeClient = mock(PatchChangeBackendClient.class);

  private final HomeLocationGate gate = new HomeLocationGate(patchChangeClient);

  private final SessionGates gates = new SessionGates(List.of(gate));

  /** The gate's refusal, which every refused read raises. */
  private final SessionGateRefusalException refusal =
      new SessionGateRefusalException("held", null, 403, gate.problemCode(), null, null);

  private final BackendApiClient backendApiClient = rejecting(BackendApiClient.class);

  private final NavigationBackendClient navigationClient = rejecting(NavigationBackendClient.class);

  private final SupplyBackendClient supplyClient = rejecting(SupplyBackendClient.class);

  private final PurchasingBackendClient purchasingClient = rejecting(PurchasingBackendClient.class);

  private final IdentityBackendClient identityClient = rejecting(IdentityBackendClient.class);

  private final MockHttpSession session = new MockHttpSession();

  @BeforeEach
  void holdTheMember() {
    when(patchChangeClient.myHomeLocationStatus()).thenReturn(new HomeLocationStatusDto(true));
    ReauthPageMvc.signInMember("KRT_MEMBER");
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "/impressum, shell/impressum",
    "/privacy, shell/privacy",
    "/terms, identity/terms",
    "/licenses, shell/licenses"
  })
  void aPageTheGateLeavesOpenRendersWithEveryChromeReadRefused(String path, String viewName)
      throws Exception {
    pages()
        .perform(get(path).session(session))
        .andExpect(redirectedUrl(null))
        .andExpect(status().isOk())
        .andExpect(view().name(viewName))
        .andExpect(model().attribute("meCapabilities", CapabilitiesResponse.NONE))
        .andExpect(model().attribute("availableSquadrons", List.of()))
        .andExpect(
            model().attribute("navigationPersonalisation", NavigationPersonalisation.NOT_LOADED))
        .andExpect(model().attribute("supplyEnabled", false))
        .andExpect(model().attribute("purchasingEnabled", false))
        .andExpect(model().attribute("sessionGatePage", true));

    verify(backendApiClient).get(eq(LAYOUT_PATH), any(Class.class));
    verify(navigationClient).preferences();
    verify(supplyClient).state();
    verify(purchasingClient).state();
  }

  @Test
  void theTermsPageShowsItsNoticeWhenTheGateRefusesTheWordingRead() throws Exception {
    pages()
        .perform(get("/terms").session(session))
        .andExpect(redirectedUrl(null))
        .andExpect(status().isOk())
        .andExpect(model().attribute("terms", nullValue()));

    verify(identityClient).publicTermsDocument();
  }

  @Test
  void theGateHoldsTheMemberOnEveryOtherPage() throws Exception {
    pages()
        .perform(get("/missions").session(session))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl(gate.pagePath()));
  }

  /**
   * Builds the legal pages and the licence notice behind the gate filter and the global error
   * handling, with every layout advice wired to the refusing clients. The filter runs outside the
   * {@code test} profile, so it holds the member on every page it does not leave open.
   *
   * @return the MockMvc
   */
  private @NotNull MockMvc pages() {
    StaticMessageSource messages = new StaticMessageSource();
    messages.setUseCodeAsDefaultMessage(true);
    FrontendAuthHelperService authHelper = new FrontendAuthHelperService();
    LayoutContextLoader layout = new LayoutContextLoader(backendApiClient, authHelper);
    NavigationRegistry registry = new NavigationRegistry();
    NavigationTabBarResolver tabBars = new NavigationTabBarResolver(registry);
    NavigationPreferencesLoader preferences = new NavigationPreferencesLoader(navigationClient);
    return MockMvcBuilders.standaloneSetup(
            new ImpressumController(),
            new PrivacyController(),
            new TermsController(identityClient),
            new OssLicensesController(mock(OssLicenseCatalog.class)))
        .setControllerAdvice(
            new GlobalExceptionHandler(messages, gates),
            new LayoutMiscAdvice(layout, messages),
            new CapabilityFlagsAdvice(layout, authHelper),
            new OrgUnitContextAdvice(backendApiClient, layout, authHelper),
            new NavigationAdvice(
                registry,
                new NullRoleHierarchy(),
                new NavigationPreferencesReader(preferences, navigationClient, registry, tabBars),
                preferences,
                authHelper,
                tabBars),
            new SupplyNavigationAdvice(new SupplyFeatureState(supplyClient), authHelper),
            new PurchasingNavigationAdvice(
                new PurchasingFeatureState(new PurchasingRequestState(purchasingClient)),
                authHelper))
        .addFilters(new SessionGateFilter(gates, new MockEnvironment()))
        .build();
  }

  /**
   * Builds a client whose every call the gate refuses.
   *
   * @param type the client type
   * @param <T> the client type
   * @return the refusing client
   */
  private <T> @NotNull T rejecting(@NotNull Class<T> type) {
    return mock(
        type,
        _ -> {
          throw refusal;
        });
  }
}
