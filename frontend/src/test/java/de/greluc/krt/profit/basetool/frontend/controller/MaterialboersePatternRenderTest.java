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
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the Materialbörse on the master-detail pattern (REQ-UI-027): page head with one create
 * menu per view, the Angebote/Gesuche tabs, the toolbar with scope segment, search, filter popover
 * and sort menu, the compact master rows, the KPI detail with one primary action, the empty state,
 * and the {@code view}/{@code scope} parameters next to the older {@code mode}/{@code tab} ones.
 */
@SpringBootTest
class MaterialboersePatternRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /materialboerse} in German.
   *
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull String query) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/materialboerse?" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * An offer of someone else's material with three interested members.
   *
   * @return the offer
   */
  private static @NotNull MaterialExchangeOfferDto offer() {
    return new MaterialExchangeOfferDto(
        UUID.fromString("00000000-0000-0000-0000-000000000071"),
        "MATERIAL",
        new MaterialReferenceDto(UUID.randomUUID(), "Quantanium", "SCU"),
        null,
        null,
        new UserReferenceDto(UUID.randomUUID(), "Vexx", "Vexx", "Vexx", null),
        List.of(),
        false,
        812,
        24.0,
        null,
        Instant.now(),
        "Teilmengen ab 8 SCU.",
        3,
        null,
        false,
        "ACTIVE",
        0L,
        false);
  }

  /**
   * A request of someone else's material.
   *
   * @return the request
   */
  private static @NotNull MaterialRequestDto request() {
    return new MaterialRequestDto(
        UUID.fromString("00000000-0000-0000-0000-000000000072"),
        "MATERIAL",
        new MaterialReferenceDto(UUID.randomUUID(), "Laranite", "SCU"),
        null,
        null,
        16.0,
        650,
        new UserReferenceDto(UUID.randomUUID(), "Nyx", "Nyx", "Nyx", null),
        List.of(),
        false,
        Instant.now(),
        "Brauche Laranite.",
        2,
        null,
        false,
        "ACTIVE",
        0L);
  }

  /**
   * Stubs the counts of both views and the given offers board.
   *
   * @param offers the offers the backend returns
   */
  private void stubOffers(@NotNull List<MaterialExchangeOfferDto> offers) {
    when(backendApiClient.get(contains("/material-exchange/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(38, 5));
    when(backendApiClient.get(contains("/material-requests/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(12, 2));
    when(backendApiClient.get(contains("/material-exchange/offers?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(offers, 0, 200, offers.size(), 1, List.of()));
    if (!offers.isEmpty()) {
      when(backendApiClient.get(
              eq("/api/v1/material-exchange/offers/{id}"), anyClass(), eq(offers.get(0).id())))
          .thenReturn(offers.get(0));
    }
  }

  /** Stubs the counts of both views and a one-request Gesuche board. */
  private void stubRequests() {
    MaterialRequestDto request = request();
    when(backendApiClient.get(contains("/material-exchange/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(38, 5));
    when(backendApiClient.get(contains("/material-requests/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(12, 2));
    when(backendApiClient.get(contains("/material-requests?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(request), 0, 200, 1, 1, List.of()));
    when(backendApiClient.get(eq("/api/v1/material-requests/{id}"), anyClass(), eq(request.id())))
        .thenReturn(request);
  }

  /** The Angebote view renders the page head, tabs, toolbar, master row and KPI detail. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void rendersTheOffersView() throws Exception {
    stubOffers(List.of(offer()));

    String html = render("");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Flotte &amp; Logistik<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    String head = html.substring(html.indexOf("class=\"page-actions\""), html.indexOf("mb-tabs"));
    assertThat(head)
        .doesNotContain("btn--cta")
        .contains("data-testid=\"mb-create-offer\"")
        .contains("data-mb-open-release=\"new\"")
        .contains("data-mb-open-item=\"item\"")
        .containsPattern("data-mb-cta-group=\"offers\"\\s*>")
        .containsPattern("data-mb-cta-group=\"requests\"\\s+hidden=\"hidden\"");
    assertThat(html)
        .containsPattern("class=\"tab active\"[^>]*data-mb-mode=\"offers\"")
        .contains("data-testid=\"mb-tab-requests\"")
        .containsPattern("<span class=\"tab-count\">38</span>")
        .containsPattern("<span class=\"tab-count\">12</span>")
        .contains("data-testid=\"segment-scope-all\"")
        .contains("data-testid=\"segment-scope-mine\"")
        .containsPattern("name=\"scope\" value=\"all\" checked=\"checked\"")
        .containsPattern("<span class=\"seg-count\">5</span>")
        .contains("data-testid=\"toolbar-search\"")
        .contains("data-testid=\"mb-filter-toggle\"")
        .contains("data-filter-chips")
        .contains("data-mb-sort")
        .containsPattern("data-testid=\"mb-exclude-stolen\"\\s*/?>");
    assertThat(html)
        .contains("data-testid=\"mb-offer-row\"")
        .contains("Quantanium")
        .contains(">Q 812<")
        .contains("class=\"mb-mrow-amount num\"")
        .contains("data-mb-counts")
        .doesNotContain("mb-mrow-int");
    String detail = html.substring(html.indexOf("id=\"mb-detail\""));
    assertThat(detail.split("class=\"kpi-total\"", -1)).hasSize(4);
    assertThat(detail)
        .containsPattern("data-testid=\"mb-interest-count\">3<")
        .containsPattern("class=\"btn btn--cta\"[^>]*data-mb-interest")
        .contains("Interesse anmelden")
        .contains("class=\"mb-dp-hint\"")
        .contains("Teilmengen ab 8 SCU.");
    assertThat(detail.substring(0, detail.indexOf("id=\"mb-modal\"")).split("btn--cta", -1))
        .hasSize(2);
  }

  /** An empty Angebote board renders the empty state with its reset instead of the master list. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void rendersTheEmptyState() throws Exception {
    stubOffers(List.of());

    String html = render("");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Angebote")
        .contains("data-mb-reset")
        .doesNotContain("data-testid=\"mb-offer-row\"")
        .doesNotContain("class=\"master-detail mb-md");
  }

  /**
   * {@code view=requests&scope=mine} renders the Gesuche with the own scope, the request create
   * menu, the stolen filter disabled, and asks the backend for the caller's own requests.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAndScopeParametersSelectTheOwnRequests() throws Exception {
    stubRequests();

    String html = render("view=requests&scope=mine");

    assertThat(html)
        .containsPattern("class=\"tab active\"[^>]*data-mb-mode=\"requests\"")
        .containsPattern("name=\"scope\" value=\"mine\" checked=\"checked\"")
        .containsPattern("<span class=\"seg-count\">2</span>")
        .containsPattern("data-mb-cta-group=\"offers\"\\s+hidden=\"hidden\"")
        .containsPattern("data-mb-cta-group=\"requests\"\\s*>")
        .containsPattern("data-testid=\"mb-exclude-stolen\"\\s+disabled=\"disabled\"")
        .contains("id=\"mg-listwrap\"")
        .doesNotContain("id=\"mb-listwrap\"")
        .contains("data-testid=\"mb-request-row\"")
        .contains(">Q≥ 650<")
        .contains("data-testid=\"mg-interest-count\">2<")
        .containsPattern("class=\"btn btn--cta\"[^>]*data-mg-interest")
        .contains("Ich kann liefern");
    verify(backendApiClient).get(contains("/material-requests?tab=mein"), anyTypeRef());
  }

  /** The older {@code mode=requests&tab=mein} link still opens the caller's own requests. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void legacyModeAndTabParametersStillWork() throws Exception {
    stubRequests();

    String html = render("mode=requests&tab=mein");

    assertThat(html)
        .containsPattern("class=\"tab active\"[^>]*data-mb-mode=\"requests\"")
        .containsPattern("name=\"scope\" value=\"mine\" checked=\"checked\"");
    verify(backendApiClient).get(contains("/material-requests?tab=mein"), anyTypeRef());
  }

  /** The board fragment of the Gesuche carries the request list and the counts for the tabs. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void boardFragmentOfTheRequestsCarriesTheCounts() throws Exception {
    stubRequests();

    String html = render("view=requests&fragment=board");

    assertThat(html)
        .contains("id=\"mg-listwrap\"")
        .contains("data-mb-counts")
        .contains("data-requests-all=\"12\"")
        .contains("data-offers-all=\"38\"")
        .doesNotContain("class=\"page-head\"");
  }
}
