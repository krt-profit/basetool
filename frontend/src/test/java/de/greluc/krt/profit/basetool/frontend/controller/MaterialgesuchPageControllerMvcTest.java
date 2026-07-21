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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC render + proxy test for the Materialbörse Gesuche (requests) surface of {@link
 * MaterialboersePageController}. Renders the real Thymeleaf request board (catching any template
 * error) with a mocked backend, proves the server-side Markdown description is rendered into the
 * page, the min-quality / desired-quantity facts render, and proves the request-edit proxy relays a
 * backend optimistic-lock conflict as a 409 with the problem code so {@code krtFetch} can offer the
 * reload-confirm.
 */
@SpringBootTest
class MaterialgesuchPageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private final UUID requestId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Stubs both count pairs (shared four-tab bar) plus a one-request board + detail. */
  private void stubBoard(MaterialRequestDto request) {
    when(backendApiClient.get(contains("/material-exchange/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(0, 0));
    when(backendApiClient.get(contains("/material-requests/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(1, 0));
    when(backendApiClient.get(contains("/material-requests?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(request), 0, 200, 1, 1, List.of()));
    // Detail lookup: match the concrete request id, NOT the broad "/material-requests/" prefix.
    // The prefix also matches "/material-requests/counts", and Mockito's last-matching-stub-wins
    // would then route the counts call here (returning a MaterialRequestDto), so loadRequestCounts
    // would hit a swallowed ClassCastException and silently render 0/0 instead of the stub.
    when(backendApiClient.get(contains("/material-requests/" + request.id()), anyClass()))
        .thenReturn(request);
  }

  private MaterialRequestDto materialRequest() {
    return new MaterialRequestDto(
        requestId,
        "MATERIAL",
        new MaterialReferenceDto(UUID.randomUUID(), "Agricium", "SCU"),
        null,
        null,
        120.0,
        600,
        new UserReferenceDto(UUID.randomUUID(), "Lenoro", "Lenoro", "Lenoro", null),
        List.of(new OrgUnitReferenceDto(UUID.randomUUID(), "IRIDIUM", "IRI", "SQUADRON")),
        true,
        Instant.now(),
        "Suche gegen **Titanium**.",
        2,
        null,
        false,
        "ACTIVE",
        0L);
  }

  /**
   * The full request board renders the material name, the tabs, the Markdown description + badge.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_requestsMode_rendersBoardAndMarkdownDescription() throws Exception {
    stubBoard(materialRequest());

    mockMvc
        .perform(get("/materialboerse").param("mode", "requests"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Agricium")))
        .andExpect(content().string(containsString("data-mb-tab")))
        .andExpect(content().string(containsString("Alle Gesuche")))
        .andExpect(content().string(containsString("<strong>Titanium</strong>")))
        .andExpect(content().string(containsString("squadron-badge")))
        .andExpect(content().string(containsString(">IRI<")))
        // A material request carries its stated minimum quality as a fact.
        .andExpect(content().string(containsString("600")))
        // The owner (mine) sees the edit CTA.
        .andExpect(content().string(containsString("data-mg-edit")))
        // The "Alle Gesuche" tab shows the stubbed request count (1) — proving the request-counts
        // lookup is honoured, not shadowed by the detail stub; every other tab-count renders 0.
        .andExpect(content().string(containsString("<span class=\"tab-count\">1</span>")));
  }

  /** A PIECE material request renders its desired amount in the piece unit, never SCU (#1182). */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_pieceMaterialRequest_rendersPieceUnitNotScu() throws Exception {
    MaterialRequestDto piece =
        new MaterialRequestDto(
            requestId,
            "MATERIAL",
            new MaterialReferenceDto(UUID.randomUUID(), "Ballistic Gatling", "PIECE"),
            null,
            null,
            12.0,
            null,
            new UserReferenceDto(UUID.randomUUID(), "Lenoro", "Lenoro", "Lenoro", null),
            List.of(),
            false,
            Instant.now(),
            "Suche.",
            0,
            null,
            false,
            "ACTIVE",
            0L);
    stubBoard(piece);

    mockMvc
        .perform(get("/materialboerse").param("mode", "requests").param("lang", "en"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("12 Piece")))
        .andExpect(content().string(not(containsString("12.000 SCU"))));
  }

  /** An item request renders its item name, the "Item" marker and its whole-piece quantity. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_itemRequest_rendersNameQuantityAndKindTag() throws Exception {
    MaterialRequestDto item =
        new MaterialRequestDto(
            requestId,
            "ITEM",
            null,
            "Venture Helmet",
            5,
            null,
            700,
            new UserReferenceDto(UUID.randomUUID(), "Lenoro", "Lenoro", "Lenoro", null),
            List.of(),
            false,
            Instant.now(),
            "Suche.",
            0,
            null,
            false,
            "ACTIVE",
            0L);
    stubBoard(item);

    mockMvc
        .perform(get("/materialboerse").param("mode", "requests").param("lang", "en"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Venture Helmet")))
        .andExpect(content().string(containsString("mb-kind-tag")))
        .andExpect(content().string(containsString("5 Piece")));
  }

  /** The Gesuche list fragment renders selectable request rows for an in-place filter swap. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void listFragment_rendersRequestRows() throws Exception {
    stubBoard(materialRequest());

    mockMvc
        .perform(get("/materialboerse").param("mode", "requests").param("fragment", "list"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-mg-select")))
        .andExpect(content().string(containsString("Agricium")));
  }

  /** The create-material-request proxy forwards to the backend request endpoint. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void createRequestProxy_forwardsToBackend() throws Exception {
    when(backendApiClient.post(eq("/api/v1/material-requests"), any(), eq(Object.class)))
        .thenReturn(Map.of("id", requestId.toString()));

    mockMvc
        .perform(
            post("/materialboerse/requests/ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"materialId\":\"" + UUID.randomUUID() + "\",\"requestedAmount\":5}"))
        .andExpect(status().isOk());

    verify(backendApiClient).post(eq("/api/v1/material-requests"), any(), eq(Object.class));
  }

  /** The request-edit proxy relays a backend optimistic-lock conflict as a 409 + problem code. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void updateRequestProxy_backendConflict_relays409() throws Exception {
    when(backendApiClient.put(contains("/material-requests/"), any(), eq(Object.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "conflict"));

    mockMvc
        .perform(
            put("/materialboerse/requests/" + requestId + "/ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"desiredAmount\":5,\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(content().string(containsString("OPTIMISTIC_LOCK")));
  }

  /** The deactivate-request proxy forwards and returns 200. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void deactivateRequestProxy_ok() throws Exception {
    when(backendApiClient.post(
            contains("/material-requests/" + requestId + "/deactivate"), any(), eq(Object.class)))
        .thenReturn(Map.of());

    mockMvc
        .perform(
            post("/materialboerse/requests/" + requestId + "/deactivate/ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());
  }

  /** The material-catalogue picker proxy forwards the free-text query to the backend search. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void requestMaterialsProxy_forwardsSearch() throws Exception {
    when(backendApiClient.get(contains("/materials/search"), anyTypeRef(), eq("agri")))
        .thenReturn(Map.of("content", List.of()));

    mockMvc
        .perform(get("/materialboerse/request-materials").param("q", "agri"))
        .andExpect(status().isOk());

    verify(backendApiClient).get(contains("/materials/search"), anyTypeRef(), eq("agri"));
  }
}
