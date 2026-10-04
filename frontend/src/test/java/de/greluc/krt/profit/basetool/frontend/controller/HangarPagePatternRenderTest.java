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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronShipDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronShipOverviewDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the hangar page on the list pattern (REQ-UI-027, REQ-HANGAR-001/002): one page with the
 * tabs „Meine Schiffe" and „Org-Einheit", the head actions with one primary action and the ⋯ menu,
 * the readiness segment and counter, the ship table with insurance chips and translated status, and
 * the org-unit tree with role-gated owner rows.
 */
@SpringBootTest
class HangarPagePatternRenderTest {

  private static final String MY_SHIPS = "/api/v1/hangar/my-ships";
  private static final String OVERVIEW = "/api/v1/hangar/squadron-overview";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders a hangar route in German.
   *
   * @param path the route with its query string
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull String path) throws Exception {
    return MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build()
        .perform(get(path).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A ship type of the given manufacturer.
   *
   * @param name the type name
   * @param maker the manufacturer name
   * @return the ship type
   */
  private static @NotNull ShipTypeDto type(@NotNull String name, @NotNull String maker) {
    ManufacturerDto manufacturer =
        new ManufacturerDto(UUID.randomUUID(), maker, "MK", null, null, null, false);
    return new ShipTypeDto(UUID.randomUUID(), name, manufacturer, name, 0, false);
  }

  /**
   * An own ship as {@code /my-ships} returns it.
   *
   * @param name the ship's own name, or {@code null}
   * @param insurance the stored insurance value
   * @param fitted whether the ship is fitted
   * @return the ship
   */
  private static @NotNull ShipDto ship(
      @Nullable String name, @NotNull String insurance, boolean fitted) {
    LocationDto area18 = new LocationDto(UUID.randomUUID(), "Area18", "City", false, false, null);
    return new ShipDto(
        UUID.randomUUID(),
        name,
        type("Cutlass Black", "Drake"),
        insurance,
        area18,
        fitted,
        null,
        null,
        3L);
  }

  /**
   * A one-page envelope of the given rows.
   *
   * @param rows the rows
   * @param total the reported total
   * @param <T> the row type
   * @return the page
   */
  private static <T> @NotNull PageResponse<T> page(@NotNull List<T> rows, long total) {
    return new PageResponse<>(rows, 0, 50, total, 1, List.of());
  }

  /**
   * Matches the opening tag of the tab link with the given test id when it carries {@code
   * aria-current="page"}, in either attribute order.
   *
   * @param testid the tab's {@code data-testid}
   * @return the pattern
   */
  private static @NotNull String currentTab(@NotNull String testid) {
    String id = "data-testid=\"" + testid + "\"";
    String current = "aria-current=\"page\"";
    return "<a[^>]*(" + id + "[^>]*" + current + "|" + current + "[^>]*" + id + ")";
  }

  /** The own-ships tab renders head, tabs, toolbar, chips, status and the readiness counter. */
  @Test
  @WithMockUser
  void rendersTheOwnShipsTab() throws Exception {
    when(backendApiClient.get(startsWith(MY_SHIPS), anyTypeRef()))
        .thenReturn(
            page(
                List.of(
                    ship("Iron Mule", "LTI", true),
                    ship(null, "0", false),
                    ship("Funke", "3", true)),
                3L));
    when(backendApiClient.get(eq(MY_SHIPS + "?page=0&size=1&fitted=true"), anyTypeRef()))
        .thenReturn(page(List.of(), 2L));
    when(backendApiClient.get(startsWith(OVERVIEW), anyTypeRef())).thenReturn(page(List.of(), 6L));

    String html = render("/hangar");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Flotte &amp; Logistik<")
        .contains("data-testid=\"hangar-import\"")
        .contains("data-testid=\"hangar-add-ship\"")
        .contains("data-testid=\"overflow-menu-toggle\"")
        .containsPattern(
            "id=\"delete-all-ships-btn\" class=\"overflow-menu__item"
                + " overflow-menu__item--danger\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("hangar-filter\"")
        .doesNotContain("import-section");
    String head =
        html.substring(
            html.indexOf("class=\"page-head\""), html.indexOf("data-testid=\"hangar-tabs\""));
    assertThat(head.split("btn--cta", -1)).hasSize(2);
    assertThat(html)
        .containsPattern(currentTab("hangar-tab-mine"))
        .containsPattern("id=\"hangar-tab-count-mine\">3<")
        .containsPattern("id=\"hangar-tab-count-unit\">6<")
        .contains("data-testid=\"segment-fitted-all\"")
        .contains("data-testid=\"segment-fitted-true\"")
        .contains("data-testid=\"segment-fitted-false\"")
        .containsPattern("name=\"fitted\" value=\"\" checked=\"checked\"")
        .containsPattern("id=\"hangar-fit-summary\"[^>]*>2 von 3 einsatzbereit<");
    assertThat(html)
        .contains("class=\"data-table data-table--stack hangar-table\"")
        .containsPattern("class=\"cell-title\">Iron Mule<")
        .contains("Drake Cutlass Black")
        .containsPattern("class=\"cell-title\">Drake Cutlass Black<")
        .contains(">ohne Namen<")
        .containsPattern("class=\"chip chip--primary\">LTI<")
        .containsPattern("class=\"chip chip--muted\">Keine<")
        .containsPattern("class=\"chip\">3 Mon\\.<")
        .contains("status-dot status-dot--on")
        .contains(">Einsatzbereit<")
        .contains(">Nicht bereit<")
        .doesNotContain(">Ja<")
        .contains("data-list-total=\"3\"");
  }

  /** The fitted segment reaches the backend and keeps the counter on the unfiltered total. */
  @Test
  @WithMockUser
  void forwardsTheFittedFilterAndDerivesTheCounter() throws Exception {
    when(backendApiClient.get(startsWith(MY_SHIPS), anyTypeRef()))
        .thenReturn(page(List.of(ship(null, "0", false)), 1L));
    when(backendApiClient.get(eq(MY_SHIPS + "?page=0&size=1"), anyTypeRef()))
        .thenReturn(page(List.of(), 4L));

    String html = render("/hangar?fitted=false");

    verify(backendApiClient).get(eq(MY_SHIPS + "?page=0&size=50&fitted=false"), anyTypeRef());
    assertThat(html)
        .containsPattern("name=\"fitted\" value=\"false\" checked=\"checked\"")
        .containsPattern("id=\"hangar-fit-summary\"[^>]*>3 von 4 einsatzbereit<")
        .containsPattern("id=\"hangar-tab-count-mine\">4<");
  }

  /** An empty hangar renders the empty state and disables the hangar-wide menu entries. */
  @Test
  @WithMockUser
  void rendersTheEmptyHangar() throws Exception {
    when(backendApiClient.get(startsWith(MY_SHIPS), anyTypeRef()))
        .thenReturn(page(List.<ShipDto>of(), 0L));

    String html = render("/hangar");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Schiffe im Hangar.")
        .doesNotContain("data-table--stack")
        .containsPattern("id=\"set-home-location-btn\"[^>]*disabled=\"disabled\"")
        .containsPattern("id=\"hangar-fit-summary\"[^>]*hidden=\"hidden\"");
  }

  /** The org-unit tab renders the tree rows and, for an officer, the hidden owner rows. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void rendersTheOrgUnitTreeWithOwnerRowsForOfficers() throws Exception {
    SquadronShipOverviewDto cutlass =
        new SquadronShipOverviewDto(
            type("Cutlass Black", "Drake"),
            4L,
            3L,
            List.of(
                new SquadronShipDetailDto("Pilot Eins", "Area18", true),
                new SquadronShipDetailDto("Pilot Zwei", null, false)));
    when(backendApiClient.get(startsWith(OVERVIEW), anyTypeRef()))
        .thenReturn(page(List.of(cutlass), 1L));
    when(backendApiClient.get(startsWith(MY_SHIPS), anyTypeRef())).thenReturn(page(List.of(), 7L));

    String html = render("/hangar/squadron");

    assertThat(html)
        .containsPattern(currentTab("hangar-tab-unit"))
        .containsPattern("id=\"hangar-tab-count-mine\">7<")
        .contains("data-list-count-for=\"squadron-results\"")
        .doesNotContain("data-testid=\"hangar-add-ship\"")
        .doesNotContain("id=\"ship-modal\"")
        .doesNotContain("btn--cta\"")
        .contains("class=\"data-table data-table--stack hangar-tree\"")
        .containsPattern("class=\"cell-title\">Cutlass Black<")
        .containsPattern("class=\"num hangar-tree__count\">4<")
        .contains("data-krtm-width=\"75\"")
        .contains(">3 von 4<")
        .contains("data-testid=\"squadron-owners-toggle\"")
        .containsPattern("class=\"hangar-tree__owners\" id=\"sq-owners-0\" hidden")
        .contains("Pilot Eins")
        .contains("Pilot Zwei")
        .doesNotContain("<thead><tr><th>Besitzer");
  }

  /** A member sees the counts but neither the owner toggle nor the owner rows. */
  @Test
  @WithMockUser
  void hidesTheOwnerRowsFromMembers() throws Exception {
    SquadronShipOverviewDto cutlass =
        new SquadronShipOverviewDto(
            type("Cutlass Black", "Drake"),
            2L,
            1L,
            List.of(new SquadronShipDetailDto("Pilot Eins", "Area18", true)));
    when(backendApiClient.get(startsWith(OVERVIEW), anyTypeRef()))
        .thenReturn(page(List.of(cutlass), 1L));

    String html = render("/hangar/squadron");

    assertThat(html)
        .contains("data-testid=\"squadron-type-row\"")
        .doesNotContain("squadron-owners-toggle")
        .doesNotContain("Pilot Eins");
  }
}
