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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.DesktopMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.PruneRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarsResponse;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.web.PurchasingFeatureState;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStateDto;
import de.greluc.krt.profit.basetool.frontend.supply.web.SupplyFeatureState;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The supply and purchasing tabs of the navigation follow the backend's tab flags (REQ-UI-026,
 * REQ-UI-032): the start-page select and the favourites offer them only to a caller who may open
 * them, and a stored one the caller lost is deleted while its surface is on.
 */
@SpringBootTest
class NavigationDataTabGateMvcTest {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";

  private static final List<String> DATA_TABS =
      List.of(
          "supply.incoming",
          "supply.stats",
          "purchasing.partners",
          "purchasing.delegates",
          "purchasing.report");

  @Autowired private WebApplicationContext context;

  @Autowired private SupplyFeatureState supplyFeatureState;

  @Autowired private PurchasingFeatureState purchasingFeatureState;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    supplyFeatureState.evict();
    purchasingFeatureState.evict();
  }

  private void surfaces(boolean on) {
    when(backendApiClient.get("/api/v1/supply/state", SupplyStateDto.class))
        .thenReturn(new SupplyStateDto(on));
    when(backendApiClient.get("/api/v1/purchasing/state", PurchasingStateDto.class))
        .thenReturn(new PurchasingStateDto(on, false, false, false, false, false, false));
  }

  private void flags(CapabilitiesResponse capabilities) {
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(LayoutResponses.withCapabilities(capabilities));
  }

  private void stored(String startPage, String... favourites) {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of(favourites), 3),
                HiddenResponse.EMPTY,
                OrderResponse.EMPTY,
                new AppearanceResponse(DesktopMode.DRAWER, true, startPage, null, 5),
                TabBarsResponse.AUTOMATIC));
  }

  @NotNull
  private String profile() throws Exception {
    return mockMvc
        .perform(
            get("/profile")
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @NotNull
  private static String memberSelect(@NotNull String html) {
    int start = html.indexOf("data-testid=\"start-page-select-member\"");
    assertThat(start).as("the member start-page select").isNotNegative();
    return html.substring(start, html.indexOf("</select>", start));
  }

  @NotNull
  private static String paletteSource(@NotNull String html) {
    int start = html.indexOf("data-testid=\"palette-source-pages\"");
    assertThat(start).as("the quick access's own page list").isNotNegative();
    return html.substring(start, html.indexOf("data-testid=\"palette-source-actions\"", start));
  }

  @Test
  @DisplayName("the quick access indexes exactly the granted tabs from the rendered page")
  void theQuickAccessIndexesTheGrantedTabs() throws Exception {
    surfaces(true);
    flags(LayoutResponses.tabs(true, false, false, false, true));
    stored(null);

    String source = paletteSource(profile());

    assertThat(source)
        .contains("data-entry-key=\"supply.incoming\"")
        .contains("data-entry-key=\"purchasing.report\"")
        .doesNotContain("data-entry-key=\"supply.stats\"")
        .doesNotContain("data-entry-key=\"purchasing.partners\"")
        .doesNotContain("data-entry-key=\"purchasing.delegates\"")
        .doesNotContainPattern("data-nav-skip=\"true\"[^>]*data-entry-key=\"purchasing.report\"");
  }

  @Test
  @DisplayName("a plain member is offered none of the five tabs as a start page")
  void aPlainMemberIsOfferedNoDataTab() throws Exception {
    surfaces(true);
    flags(LayoutResponses.tabs(false, false, false, false, false));
    stored(null);

    String select = memberSelect(profile());

    for (String key : DATA_TABS) {
      assertThat(select).doesNotContain("<option value=\"" + key + "\"");
    }
    assertThat(select).contains("<option value=\"supply.needs\"");
  }

  @Test
  @DisplayName("each tab is offered as a start page exactly with its own flag")
  void eachTabIsOfferedWithItsOwnFlag() throws Exception {
    surfaces(true);
    flags(LayoutResponses.tabs(false, true, false, false, true));
    stored(null);

    String select = memberSelect(profile());

    assertThat(select)
        .contains("<option value=\"supply.stats\"")
        .contains("<option value=\"purchasing.report\"")
        .doesNotContain("<option value=\"supply.incoming\"")
        .doesNotContain("<option value=\"purchasing.partners\"")
        .doesNotContain("<option value=\"purchasing.delegates\"");
  }

  @Test
  @DisplayName(
      "a lost tab is deleted from the favourites and the start page while its surface is on")
  void aLostTabIsDeletedWhileTheSurfaceIsOn() throws Exception {
    surfaces(true);
    flags(LayoutResponses.tabs(false, false, false, false, false));
    stored("purchasing.report", "purchasing.report", "supply.stats", "refinery.orders");
    when(backendApiClient.post(
            eq(PREFERENCES + "/prune"), any(), eq(NavigationPreferencesResponse.class)))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of("refinery.orders"), 4),
                HiddenResponse.EMPTY,
                OrderResponse.EMPTY,
                new AppearanceResponse(DesktopMode.DRAWER, true, null, null, 6),
                TabBarsResponse.AUTOMATIC));

    String html = profile();

    ArgumentCaptor<PruneRequest> pruned = ArgumentCaptor.forClass(PruneRequest.class);
    verify(backendApiClient)
        .post(
            eq(PREFERENCES + "/prune"), pruned.capture(), eq(NavigationPreferencesResponse.class));
    assertThat(pruned.getValue().keys())
        .contains("purchasing.report", "supply.stats")
        .doesNotContain("refinery.orders");
    assertThat(html)
        .doesNotContain("nav-favourite-purchasing.report")
        .doesNotContain("nav-favourite-supply.stats")
        .contains("nav-favourite-refinery.orders");
  }

  @Test
  @DisplayName("a tab the caller still holds stays a favourite")
  void aHeldTabStaysAFavourite() throws Exception {
    surfaces(true);
    flags(LayoutResponses.tabs(false, false, false, false, true));
    stored(null, "purchasing.report");

    String html = profile();

    verify(backendApiClient, never())
        .post(eq(PREFERENCES + "/prune"), any(), eq(NavigationPreferencesResponse.class));
    assertThat(html).contains("nav-favourite-purchasing.report");
  }

  @Test
  @DisplayName("a switched-off surface deletes no stored tab")
  void aSwitchedOffSurfaceDeletesNothing() throws Exception {
    surfaces(false);
    flags(LayoutResponses.tabs(false, false, false, false, false));
    stored("purchasing.report", "purchasing.report", "supply.stats");

    profile();

    verify(backendApiClient, never())
        .post(eq(PREFERENCES + "/prune"), any(), eq(NavigationPreferencesResponse.class));
  }
}
