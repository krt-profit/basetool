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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDemandGroupDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDemandOrderShareDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDemandOverviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDemandRowDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the cross-order material-demand page ({@code /orders/material-demand}, REQ-ORDERS-034)
 * through the real Thymeleaf template, so a broken expression in the per-org-unit grouping, the
 * four amount columns or the contributing-order drill-down fails the build instead of only
 * surfacing at runtime.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderMaterialDemandRenderTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Builds an authenticated LOGISTICIAN principal — the role that actually works a gathering run,
   * and one that passes the page's {@code isAuthenticated()} gate.
   *
   * @param userId the principal's subject id.
   * @return the authentication token.
   */
  private OAuth2AuthenticationToken logisticianToken(UUID userId) {
    Map<String, Object> claims = new HashMap<>();
    claims.put(IdTokenClaimNames.SUB, userId.toString());
    claims.put("preferred_username", "logistician");
    OidcIdToken idToken =
        new OidcIdToken(
            "token-value",
            java.time.Instant.now(),
            java.time.Instant.now().plusSeconds(3600),
            claims);
    OidcUser oidcUser =
        new DefaultOidcUser(
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")), idToken);
    return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");
  }

  /**
   * Builds a material reference with the given unit, enough for the template's unit-aware
   * formatting.
   *
   * @param name the material name.
   * @param quantityType {@code SCU} or {@code PIECE}.
   * @return the material DTO.
   */
  private static MaterialDto material(String name, String quantityType) {
    return new MaterialDto(
        UUID.randomUUID(),
        name,
        "MINERAL",
        quantityType,
        null,
        null,
        null,
        false,
        false,
        false,
        false,
        true,
        false,
        true,
        1L);
  }

  @Test
  void demandPage_rendersPerOrgUnitGroupsWithAllFourAmountColumns() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    MaterialDto titanium = material("Titanium", "SCU");

    MaterialDemandRowDto row =
        new MaterialDemandRowDto(
            titanium,
            "GOOD",
            1000.0,
            250.0,
            100.0,
            750.0,
            List.of(
                new MaterialDemandOrderShareDto(
                    orderId, 42, "IN_PROGRESS", "MATERIAL", 1000.0, 250.0, 100.0)));
    MaterialDemandOverviewDto overview =
        new MaterialDemandOverviewDto(
            List.of(
                new MaterialDemandGroupDto(
                    new SquadronReferenceDto(UUID.randomUUID(), "Iridium", "IRI"), List.of(row))));
    when(backendApiClient.get(contains("/material-demand"), anyClass())).thenReturn(overview);

    String html =
        mockMvc
            .perform(
                get("/orders/material-demand")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("one section per responsible org unit")
        .contains("data-testid=\"demand-group\"");
    assertThat(html).as("the org unit's badge").contains(">IRI<");
    assertThat(html).as("the material name").contains("Titanium");
    assertThat(html).as("required amount").contains("1000,000");
    assertThat(html).as("booked amount").contains("250,000");
    assertThat(html).as("claimed amount").contains("100,000");
    assertThat(html).as("outstanding gap").contains("750,000");
    assertThat(html).as("quality bucket badge").contains("quality-good");
  }

  @Test
  void demandPage_drillDownListsContributingOrdersAndStartsCollapsed() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    MaterialDemandRowDto row =
        new MaterialDemandRowDto(
            material("Laranite", "SCU"),
            "NONE",
            60.0,
            0.0,
            0.0,
            60.0,
            List.of(new MaterialDemandOrderShareDto(orderId, 7, "OPEN", "ITEM", 60.0, 0.0, 0.0)));
    when(backendApiClient.get(contains("/material-demand"), anyClass()))
        .thenReturn(
            new MaterialDemandOverviewDto(
                List.of(
                    new MaterialDemandGroupDto(
                        new SquadronReferenceDto(UUID.randomUUID(), "Iridium", "IRI"),
                        List.of(row)))));

    String html =
        mockMvc
            .perform(
                get("/orders/material-demand")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("contributing order linked by display id").contains("#7");
    assertThat(html).as("drill-down links to the order detail").contains("/orders/" + orderId);
    assertThat(html).as("an item order still contributes material").contains("order-kind-item");
    assertThat(html).as("drill-down starts hidden").contains("krtm-display-none-5790");
    assertThat(html).as("toggle reports the collapsed state").contains("aria-expanded=\"false\"");
  }

  @Test
  void demandPage_pieceMaterialRendersWholeUnits() throws Exception {
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(contains("/material-demand"), anyClass()))
        .thenReturn(
            new MaterialDemandOverviewDto(
                List.of(
                    new MaterialDemandGroupDto(
                        new SquadronReferenceDto(UUID.randomUUID(), "Iridium", "IRI"),
                        List.of(
                            new MaterialDemandRowDto(
                                material("Quantum Core", "PIECE"),
                                "NONE",
                                12.0,
                                4.0,
                                0.0,
                                8.0,
                                List.of()))))));

    String html =
        mockMvc
            .perform(
                get("/orders/material-demand")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("whole-unit demand").contains(">12 ");
    assertThat(html).as("no SCU decimals on a PIECE row").doesNotContain("12,000");
  }

  @Test
  void demandPage_noVisibleOrders_rendersEmptyState() throws Exception {
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(contains("/material-demand"), anyClass()))
        .thenReturn(new MaterialDemandOverviewDto(List.of()));

    String html =
        mockMvc
            .perform(
                get("/orders/material-demand")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("no group sections").doesNotContain("data-testid=\"demand-group\"");
    assertThat(html).as("empty state copy").contains("keine offenen oder in Bearbeitung");
    assertThat(html)
        .as("no stray unbound amount leaked from the fragment declaration")
        .doesNotContain("0.000 SCU")
        .doesNotContain("0,000 SCU");
  }

  @Test
  void demandPage_backendUnavailable_degradesToEmptyStateInsteadOfErrorPage() throws Exception {
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(contains("/material-demand"), anyClass()))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(
            get("/orders/material-demand")
                .header("Accept-Language", "de")
                .with(authentication(logisticianToken(userId))))
        .andExpect(status().isOk());
  }

  @Test
  void demandPage_rendersTheCollapsibleFilterPanelAndSortableHeaders() throws Exception {
    UUID userId = UUID.randomUUID();
    MaterialDto titanium = material("Titanium", "SCU");
    when(backendApiClient.get(contains("/material-demand"), anyClass()))
        .thenReturn(
            new MaterialDemandOverviewDto(
                List.of(
                    new MaterialDemandGroupDto(
                        new SquadronReferenceDto(UUID.randomUUID(), "Iridium", "IRI"),
                        List.of(
                            new MaterialDemandRowDto(
                                titanium, "GOOD", 10.0, 0.0, 0.0, 10.0, List.of()))))));

    String html =
        mockMvc
            .perform(
                get("/orders/material-demand")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("filter toggle").contains("data-testid=\"demand-filter-toggle\"");
    assertThat(html)
        .as("toggle controls the panel")
        .contains("aria-controls=\"demandFilterPanel\"");
    assertThat(html).as("panel starts expanded server-side").contains("aria-expanded=\"true\"");
    assertThat(html)
        .as("active-filter chip template")
        .contains("data-label=\"Aktive Filter: {0}\"");
    assertThat(html).as("material multi-select").contains("id=\"demandMaterialOptions\"");
    assertThat(html).as("in-dropdown search").contains("data-testid=\"demand-material-search\"");
    assertThat(html).as("quality filters").contains("data-testid=\"demand-quality-good\"");
    assertThat(html).as("hide-covered toggle").contains("data-testid=\"demand-hide-covered\"");

    assertThat(html).as("sortable outstanding header").contains("data-sort-key=\"outstanding\"");
    assertThat(html).as("unsorted by default").contains("aria-sort=\"none\"");
    assertThat(html).as("numeric sort key on the row").contains("data-outstanding=\"10.0\"");
    assertThat(html).as("material sort key on the row").contains("data-material-name=\"Titanium\"");
  }

  @Test
  void demandPage_materialFilterListsEachMaterialOnce() throws Exception {
    UUID userId = UUID.randomUUID();
    MaterialDto titanium = material("Titanium", "SCU");
    MaterialDemandRowDto good =
        new MaterialDemandRowDto(titanium, "GOOD", 10.0, 0.0, 0.0, 10.0, List.of());
    MaterialDemandRowDto none =
        new MaterialDemandRowDto(titanium, "NONE", 5.0, 0.0, 0.0, 5.0, List.of());
    when(backendApiClient.get(contains("/material-demand"), anyClass()))
        .thenReturn(
            new MaterialDemandOverviewDto(
                List.of(
                    new MaterialDemandGroupDto(
                        new SquadronReferenceDto(UUID.randomUUID(), "Iridium", "IRI"),
                        List.of(good, none)),
                    new MaterialDemandGroupDto(
                        new SquadronReferenceDto(UUID.randomUUID(), "Nova", "NOV"),
                        List.of(good)))));

    String html =
        mockMvc
            .perform(
                get("/orders/material-demand")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html.split("demand-material-option", -1).length - 1)
        .as("one filter option per distinct material, across groups and quality buckets")
        .isEqualTo(1);
  }

  @Test
  void demandPage_fragmentRequest_returnsOnlyTheResultsFragment() throws Exception {
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(contains("/material-demand"), anyClass()))
        .thenReturn(new MaterialDemandOverviewDto(List.of()));

    String html =
        mockMvc
            .perform(
                get("/orders/material-demand?fragment=results")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("fragment only").doesNotContain("<html");
    assertThat(html).as("fragment only").doesNotContain("id=\"hamburger\"");
  }
}
