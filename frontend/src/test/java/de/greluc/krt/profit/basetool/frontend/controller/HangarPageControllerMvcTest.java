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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronShipOverviewDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class HangarPageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser
  void viewHangar_ShouldRenderHangarView() throws Exception {
    // Given
    PageResponse<ShipDto> ships = new PageResponse<>(List.of(), 0, 10, 0, 1, List.of());

    ShipTypeDto shipType =
        new ShipTypeDto(UUID.randomUUID(), "Avenger Titan", null, "Titan", 0, false);
    PageResponse<ShipTypeDto> shipTypes =
        new PageResponse<>(List.of(shipType), 0, 10, 1, 1, List.of());

    LocationDto location = new LocationDto(UUID.randomUUID(), "Area18", "City", false, false, null);
    PageResponse<LocationDto> locations =
        new PageResponse<>(List.of(location), 0, 10, 1, 1, List.of());

    when(backendApiClient.get(eq("/api/v1/hangar/my-ships?page=0&size=50"), anyTypeRef()))
        .thenReturn(ships);
    when(backendApiClient.getCached(eq("/api/v1/ship-types?size=1000"), anyTypeRef()))
        .thenReturn(shipTypes);
    when(backendApiClient.getCached(eq("/api/v1/locations?size=1000"), anyTypeRef()))
        .thenReturn(locations);

    // When & Then
    mockMvc.perform(get("/hangar")).andExpect(status().isOk()).andExpect(view().name("hangar"));
  }

  @Test
  @WithMockUser
  void viewHangar_ShouldRenderServerSideSearchFormWhenHangarHasShips() throws Exception {
    // The personal hangar's text filter is now a server-side GET form (REQ-HANGAR-002): the search
    // input carries name="search" so it round-trips to /hangar, and the rendered ship type appears.
    ManufacturerDto manufacturer =
        new ManufacturerDto(UUID.randomUUID(), "Aegis Dynamics", "AEGS", null, null, null, false);
    ShipTypeDto shipType =
        new ShipTypeDto(UUID.randomUUID(), "Avenger Titan", manufacturer, "Titan", 0, false);
    ShipDto ship =
        new ShipDto(UUID.randomUUID(), "My Titan", shipType, "LTI", null, true, null, null, 0L);
    PageResponse<ShipDto> ships = new PageResponse<>(List.of(ship), 0, 50, 1, 1, List.of());

    when(backendApiClient.get(eq("/api/v1/hangar/my-ships?page=0&size=50"), anyTypeRef()))
        .thenReturn(ships);

    // When & Then
    mockMvc
        .perform(get("/hangar"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar"))
        .andExpect(content().string(containsString("id=\"hangar-filter-form\"")))
        .andExpect(content().string(containsString("id=\"hangar-ship-filter\"")))
        .andExpect(content().string(containsString("name=\"search\"")))
        .andExpect(content().string(containsString("Avenger Titan")));
  }

  @Test
  @WithMockUser
  void viewHangar_ShouldRenderHomeLocationButtonAndCuratedOptions() throws Exception {
    // Given a hangar with at least one ship (button is hidden when empty) and one curated home
    // location returned by the dedicated endpoint.
    ManufacturerDto manufacturer =
        new ManufacturerDto(UUID.randomUUID(), "Aegis Dynamics", "AEGS", null, null, null, false);
    ShipTypeDto shipType =
        new ShipTypeDto(UUID.randomUUID(), "Avenger Titan", manufacturer, "Titan", 0, false);
    ShipDto ship =
        new ShipDto(UUID.randomUUID(), "My Titan", shipType, "LTI", null, true, null, null, 0L);
    PageResponse<ShipDto> ships = new PageResponse<>(List.of(ship), 0, 1000, 1, 1, List.of());

    LocationDto homeLoc = new LocationDto(UUID.randomUUID(), "Orison", null, false, true, 1L);

    when(backendApiClient.get(eq("/api/v1/hangar/my-ships?page=0&size=50"), anyTypeRef()))
        .thenReturn(ships);
    when(backendApiClient.getCached(eq("/api/v1/locations/home-locations"), anyTypeRef()))
        .thenReturn(List.of(homeLoc));

    // When & Then
    mockMvc
        .perform(get("/hangar"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar"))
        .andExpect(content().string(containsString("id=\"set-home-location-btn\"")))
        .andExpect(content().string(containsString("id=\"home-location-modal\"")))
        .andExpect(content().string(containsString("Orison")));
  }

  @Test
  @WithMockUser
  void viewHangar_FragmentResults_RendersOnlyTheShipTableFragment() throws Exception {
    // The in-place swap target: GET /hangar?fragment=results returns just the hangarResults
    // fragment (the add button + search form + table + pagination) and NOT the surrounding page
    // chrome (the modals, the import section), so a write/filter/page handler can re-render the
    // table without reloading (REQ-FE-005 / REQ-HANGAR-002).
    ManufacturerDto manufacturer =
        new ManufacturerDto(UUID.randomUUID(), "Aegis Dynamics", "AEGS", null, null, null, false);
    ShipTypeDto shipType =
        new ShipTypeDto(UUID.randomUUID(), "Avenger Titan", manufacturer, "Titan", 0, false);
    ShipDto ship =
        new ShipDto(UUID.randomUUID(), "My Titan", shipType, "LTI", null, true, null, null, 0L);
    PageResponse<ShipDto> ships = new PageResponse<>(List.of(ship), 0, 50, 1, 1, List.of());

    when(backendApiClient.get(eq("/api/v1/hangar/my-ships?page=0&size=50"), anyTypeRef()))
        .thenReturn(ships);

    mockMvc
        .perform(get("/hangar").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar :: hangarResults"))
        .andExpect(content().string(containsString("data-testid=\"hangar-add-ship\"")))
        .andExpect(content().string(containsString("id=\"hangar-ship-filter\"")))
        // the modals + import section live outside the fragment and must not be in the swap body
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("id=\"ship-modal\""))))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(containsString("id=\"fleetview-import-btn\""))));
  }

  @Test
  @WithMockUser
  void viewSquadron_ShouldRenderSearchFormAndPageSizePicker() throws Exception {
    // Given a first page at the default size 50 with enough total entries (>10) for the
    // page-size picker to render at all.
    ManufacturerDto manufacturer =
        new ManufacturerDto(
            UUID.randomUUID(), "Drake Interplanetary", "DRAK", null, null, null, false);
    ShipTypeDto shipType =
        new ShipTypeDto(UUID.randomUUID(), "Cutlass Black", manufacturer, null, 0, false);
    SquadronShipOverviewDto overview = new SquadronShipOverviewDto(shipType, 3L, 1L, List.of());
    PageResponse<SquadronShipOverviewDto> page =
        new PageResponse<>(List.of(overview), 0, 50, 12, 1, List.of());

    when(backendApiClient.get(eq("/api/v1/hangar/squadron-overview?page=0&size=50"), anyTypeRef()))
        .thenReturn(page);

    // When & Then: the server-side search form and the 10/50/100 page-size picker render;
    // the active size (50) is an inert span, the other sizes are links.
    mockMvc
        .perform(get("/hangar/squadron"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar-squadron"))
        .andExpect(content().string(containsString("id=\"squadron-filter-form\"")))
        .andExpect(content().string(containsString("id=\"squadron-ship-filter\"")))
        .andExpect(content().string(containsString("page-size-picker")))
        .andExpect(content().string(containsString("/hangar/squadron?page=0&amp;size=10")))
        .andExpect(content().string(containsString("/hangar/squadron?page=0&amp;size=100")))
        .andExpect(content().string(containsString("Cutlass Black")));
  }

  @Test
  @WithMockUser
  void viewSquadron_ShouldSnapUnsupportedPageSizeToDefault() throws Exception {
    // covers REQ-HANGAR-001 — a crafted ?size= outside 10/50/100 must not reach the backend;
    // it snaps to the default page size (50) instead.
    PageResponse<SquadronShipOverviewDto> page =
        new PageResponse<>(List.of(), 0, 50, 0, 1, List.of());
    when(backendApiClient.get(eq("/api/v1/hangar/squadron-overview?page=0&size=50"), anyTypeRef()))
        .thenReturn(page);

    mockMvc
        .perform(get("/hangar/squadron").param("size", "5000"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar-squadron"));
  }

  @Test
  @WithMockUser
  void viewSquadron_ShouldForwardSearchAndKeepItInPaginationLinks() throws Exception {
    // covers REQ-HANGAR-001 — the search term reaches the backend, and the page-size links
    // carry it so switching the size never silently drops the active filter.
    PageResponse<SquadronShipOverviewDto> page =
        new PageResponse<>(List.of(), 0, 50, 12, 1, List.of());
    when(backendApiClient.get(
            eq("/api/v1/hangar/squadron-overview?page=0&size=50&search=Cutlass"), anyTypeRef()))
        .thenReturn(page);

    mockMvc
        .perform(get("/hangar/squadron").param("search", "Cutlass"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar-squadron"))
        .andExpect(
            content()
                .string(containsString("/hangar/squadron?search=Cutlass&amp;page=0&amp;size=10")))
        // the clear-filter link drops the search but keeps the page size
        .andExpect(content().string(containsString("href=\"/hangar/squadron?size=50\"")));
  }
}
