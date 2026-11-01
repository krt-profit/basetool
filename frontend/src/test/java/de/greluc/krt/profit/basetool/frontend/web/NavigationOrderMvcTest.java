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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderModeResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResetRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderScopeUpdateRequest;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the stored order of REQ-UI-032 through the layout: groups and links in the stored order
 * with keys the store does not know placed after their default predecessor, the up/down controls
 * and the write relays.
 */
@SpringBootTest
class NavigationOrderMvcTest {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private void stored(OrderModeResponse member, OrderModeResponse admin) {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenReturn(
            new NavigationPreferencesResponse(
                new FavouritesResponse(List.of(), 0),
                HiddenResponse.EMPTY,
                new OrderResponse(member, admin)));
  }

  private static OrderModeResponse order(long version, Map<String, List<String>> scopes) {
    return new OrderModeResponse(scopes, version);
  }

  private static RequestPostProcessor member() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private static RequestPostProcessor admin() {
    return oidcLogin()
        .authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  @NotNull
  private String page(@NotNull RequestPostProcessor user) throws Exception {
    return mockMvc
        .perform(get("/impressum").with(user))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @NotNull
  private static String row(@NotNull String html, @NotNull String key) {
    int start = html.indexOf("<div class=\"nav-row\" data-entry-key=\"" + key + "\"");
    assertThat(start).as("row of " + key).isNotNegative();
    return html.substring(start, html.indexOf("</div>", html.indexOf("</div>", start) + 1));
  }

  private static int at(@NotNull String html, @NotNull String marker) {
    int index = html.indexOf(marker);
    assertThat(index).as(marker).isNotNegative();
    return index;
  }

  @Test
  @DisplayName("without a stored order the groups and links keep their default order")
  void keepsTheDefaultOrder() throws Exception {
    stored(OrderModeResponse.EMPTY, OrderModeResponse.EMPTY);

    String html = page(member());

    assertThat(at(html, "data-group-key=\"operations\""))
        .isLessThan(at(html, "data-group-key=\"logistics\""));
    assertThat(at(html, "data-entry-key=\"missions.list\""))
        .isLessThan(at(html, "data-entry-key=\"hangar.overview\""));
    assertThat(html).contains("data-order-member-version=\"0\"");
  }

  @Test
  @DisplayName(
      "a stored group order moves the groups and places unknown groups after their predecessor")
  void ordersTheGroupsAndPlacesUnknownOnes() throws Exception {
    stored(order(3, Map.of("groups", List.of("logistics", "operations"))), OrderModeResponse.EMPTY);

    String html = page(member());

    int logistics = at(html, "data-group-key=\"logistics\"");
    int trade = at(html, "data-group-key=\"trade\"");
    int unit = at(html, "data-group-key=\"unit\"");
    int operations = at(html, "data-group-key=\"operations\"");
    assertThat(logistics).isLessThan(trade);
    assertThat(trade).isLessThan(unit);
    assertThat(unit).isLessThan(operations);
    assertThat(html).contains("data-order-member-version=\"3\"");
  }

  @Test
  @DisplayName("a stored link order moves the links inside their group")
  void ordersTheLinksOfAGroup() throws Exception {
    stored(
        order(1, Map.of("operations", List.of("hangar.overview", "missions.list"))),
        OrderModeResponse.EMPTY);

    String html = page(member());

    int hangar = at(html, "<div class=\"nav-row\" data-entry-key=\"hangar.overview\"");
    int missions = at(html, "<div class=\"nav-row\" data-entry-key=\"missions.list\"");
    assertThat(hangar).isLessThan(missions);
  }

  @Test
  @DisplayName(
      "a stored key of a link that belongs to another group never moves it out of its group")
  void aLinkNeverChangesGroup() throws Exception {
    stored(
        order(1, Map.of("operations", List.of("refinery.orders", "missions.list"))),
        OrderModeResponse.EMPTY);

    String html = page(member());

    int logistics = at(html, "data-group-key=\"logistics\"");
    assertThat(at(html, "<div class=\"nav-row\" data-entry-key=\"refinery.orders\""))
        .isGreaterThan(logistics);
  }

  @Test
  @DisplayName("every group and link carries up and down controls, the ends marked unavailable")
  void rendersTheMoveControls() throws Exception {
    stored(OrderModeResponse.EMPTY, OrderModeResponse.EMPTY);

    String html = page(member());

    String first = row(html, "missions.list");
    assertThat(first)
        .contains("data-reorder-move=\"up\"")
        .contains("data-reorder-move=\"down\"")
        .contains("data-reorder-name=\"")
        .containsPattern("data-reorder-move=\"up\"[^>]*aria-disabled=\"true\"");
    assertThat(html).contains("nav-group-controls").contains("data-nav-live");
    assertThat(html).contains("data-reorder-announce=");
    assertThat(row(html, "personal.profile")).doesNotContain("data-reorder-move");
  }

  @Test
  @DisplayName("the reset control is disabled without a stored order and enabled with one")
  void enablesTheResetOnlyWithAStoredOrder() throws Exception {
    stored(OrderModeResponse.EMPTY, OrderModeResponse.EMPTY);
    assertThat(page(member())).containsPattern("data-testid=\"nav-order-reset\"[^>]*disabled");

    stored(order(2, Map.of("groups", List.of("logistics", "operations"))), OrderModeResponse.EMPTY);
    assertThat(page(member()))
        .doesNotContainPattern("data-testid=\"nav-order-reset\"[^>]*disabled");
  }

  @Test
  @DisplayName("the administration order applies to the administration groups with its own version")
  void ordersTheAdministrationGroups() throws Exception {
    stored(
        OrderModeResponse.EMPTY,
        order(
            5, Map.of("groups", List.of("admin-operations", "admin-masterdata", "admin-members"))));

    String html = page(admin());

    assertThat(at(html, "data-group-key=\"admin-operations\""))
        .isLessThan(at(html, "data-group-key=\"admin-members\""));
    assertThat(html)
        .contains("data-order-admin-version=\"5\"")
        .contains("data-order-member-version=\"0\"");
  }

  @Test
  @DisplayName("when the preferences cannot be read the default order shows and no move control")
  void failsClosedToTheDefaultOrder() throws Exception {
    when(backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class))
        .thenThrow(new BackendServiceException("down", null, 503));

    assertThat(page(member())).doesNotContain("data-reorder-move");
  }

  @Test
  @DisplayName("the swap fragment carries the ordered lists and the order versions")
  void rendersTheSwapFragment() throws Exception {
    stored(order(7, Map.of("groups", List.of("logistics", "operations"))), OrderModeResponse.EMPTY);

    String html =
        mockMvc
            .perform(
                get("/navigation/menu").with(member()).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("data-order-member-version=\"7\"");
    assertThat(at(html, "data-group-key=\"logistics\""))
        .isLessThan(at(html, "data-group-key=\"operations\""));
  }

  @Test
  @DisplayName("the scope write relays the stored order with its new version")
  void relaysTheScopeWrite() throws Exception {
    when(backendApiClient.put(
            eq(PREFERENCES + "/order/member/groups"),
            eq(new OrderScopeUpdateRequest(List.of("logistics", "operations"), 1L)),
            eq(OrderModeResponse.class)))
        .thenReturn(order(2, Map.of("groups", List.of("logistics", "operations"))));

    mockMvc
        .perform(
            put("/navigation/order/member/groups")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"logistics\",\"operations\"],\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
  }

  @Test
  @DisplayName("the scope write refuses an incomplete payload and a malformed path")
  void refusesIncompleteOrMalformedWrites() throws Exception {
    mockMvc
        .perform(
            put("/navigation/order/member/groups")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"logistics\"]}"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION"));
    mockMvc
        .perform(
            put("/navigation/order/member/Not_A_Key")
                .with(member())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[],\"version\":0}"))
        .andExpect(status().is(422));
  }

  @Test
  @DisplayName("the reset relays the empty order with its new version")
  void relaysTheReset() throws Exception {
    when(backendApiClient.post(
            eq(PREFERENCES + "/order/admin/reset"),
            eq(new OrderResetRequest(4L)),
            eq(OrderModeResponse.class)))
        .thenReturn(order(5, Map.of()));

    mockMvc
        .perform(
            post("/navigation/order/admin/reset")
                .with(admin())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(5));
  }
}
