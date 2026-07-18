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
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC render + proxy test for {@link MaterialboersePageController}. Renders the real Thymeleaf
 * master-detail (catching any template error) with a mocked backend, proves the server-side
 * Markdown remark is rendered into the page, and proves the remark-edit proxy relays a backend
 * optimistic-lock conflict as a 409 with the problem code so {@code krtFetch} can offer the
 * reload-confirm.
 */
@SpringBootTest
class MaterialboersePageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private final UUID offerId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private void stubBoard() {
    MaterialExchangeOfferDto offer =
        new MaterialExchangeOfferDto(
            offerId,
            "MATERIAL",
            new MaterialReferenceDto(UUID.randomUUID(), "Agricium", "SCU"),
            null,
            null,
            new UserReferenceDto(UUID.randomUUID(), "Lenoro", "Lenoro", "Lenoro", null),
            List.of(new OrgUnitReferenceDto(UUID.randomUUID(), "IRIDIUM", "IRI", "SQUADRON")),
            false,
            796,
            120.0,
            340.0,
            Instant.now(),
            "Tausche gegen **Titanium**.",
            2,
            null,
            false,
            "ACTIVE",
            0L);
    when(backendApiClient.get(contains("/material-exchange/offers?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(offer), 0, 200, 1, 1, List.of()));
    when(backendApiClient.get(contains("/material-exchange/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(1, 0));
    when(backendApiClient.get(contains("/material-exchange/offers/"), anyClass()))
        .thenReturn(offer);
  }

  /** The full page renders with the title, an offer row and the server-rendered Markdown remark. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_rendersMasterDetailAndRenderedMarkdown() throws Exception {
    stubBoard();

    mockMvc
        .perform(get("/materialboerse"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Agricium")))
        .andExpect(content().string(containsString("data-mb-tab")))
        .andExpect(content().string(containsString("<strong>Titanium</strong>")))
        // The Anbieter's org-unit affiliation badge renders next to the username.
        .andExpect(content().string(containsString("squadron-badge")))
        .andExpect(content().string(containsString(">IRI<")));
  }

  /**
   * A PIECE material renders its amount as an integer count in the piece unit ("12 Piece"), never
   * as SCU — the regression from issue #1182 where every offer was shown as SCU. The locale is
   * pinned to English via {@code ?lang} so the assertion stays ASCII.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_pieceMaterial_rendersPieceUnitNotScu() throws Exception {
    MaterialExchangeOfferDto piece =
        new MaterialExchangeOfferDto(
            offerId,
            "MATERIAL",
            new MaterialReferenceDto(UUID.randomUUID(), "Ballistic Gatling", "PIECE"),
            null,
            null,
            new UserReferenceDto(UUID.randomUUID(), "Lenoro", "Lenoro", "Lenoro", null),
            List.of(new OrgUnitReferenceDto(UUID.randomUUID(), "IRIDIUM", "IRI", "SQUADRON")),
            false,
            500,
            12.0,
            null,
            Instant.now(),
            "Tausche.",
            0,
            null,
            false,
            "ACTIVE",
            0L);
    when(backendApiClient.get(contains("/material-exchange/offers?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(piece), 0, 200, 1, 1, List.of()));
    when(backendApiClient.get(contains("/material-exchange/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(1, 0));

    mockMvc
        .perform(get("/materialboerse").param("lang", "en"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("12 Piece")))
        .andExpect(content().string(not(containsString("12.000 SCU"))))
        .andExpect(content().string(not(containsString("12,000 SCU"))));
  }

  /**
   * An item offer (#1185) renders with its item name, its whole-piece quantity and the "Item"
   * marker, and never a quality (item offers have none — no stray "Q null"). The locale is pinned
   * to English via {@code ?lang} so the assertion stays ASCII.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_itemOffer_rendersNameQuantityAndKindTagWithoutQuality() throws Exception {
    MaterialExchangeOfferDto item =
        new MaterialExchangeOfferDto(
            offerId,
            "ITEM",
            null,
            "Venture Helmet",
            7,
            new UserReferenceDto(UUID.randomUUID(), "Lenoro", "Lenoro", "Lenoro", null),
            List.of(new OrgUnitReferenceDto(UUID.randomUUID(), "IRIDIUM", "IRI", "SQUADRON")),
            false,
            null,
            null,
            null,
            Instant.now(),
            "Trade for **aUEC**.",
            0,
            null,
            false,
            "ACTIVE",
            0L);
    when(backendApiClient.get(contains("/material-exchange/offers?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(item), 0, 200, 1, 1, List.of()));
    when(backendApiClient.get(contains("/material-exchange/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(1, 0));
    when(backendApiClient.get(contains("/material-exchange/offers/"), anyClass())).thenReturn(item);

    mockMvc
        .perform(get("/materialboerse").param("lang", "en"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Venture Helmet")))
        .andExpect(content().string(containsString("7 Piece")))
        .andExpect(content().string(containsString("mb-kind-tag")))
        .andExpect(content().string(not(containsString("Q null"))));
  }

  /**
   * A <b>stock-backed</b> item offer the viewer owns (REQ-MARKET-014) renders the edit CTA — unlike
   * a free-stated item offer, its quantity is stock-backed so it is editable. The button carries
   * the {@code data-kind="ITEM"} / {@code data-quantity-type="PIECE"} attributes and the item
   * quantity as {@code data-amount}, and the template never dereferences the null {@code
   * material()} of an item offer. The locale is pinned to English via {@code ?lang} so the
   * assertion stays ASCII.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_stockBackedItemOffer_mine_rendersEditCta() throws Exception {
    MaterialExchangeOfferDto stockBacked =
        new MaterialExchangeOfferDto(
            offerId,
            "ITEM",
            null,
            "Quantum Drive",
            5,
            new UserReferenceDto(UUID.randomUUID(), "Lenoro", "Lenoro", "Lenoro", null),
            List.of(new OrgUnitReferenceDto(UUID.randomUUID(), "IRIDIUM", "IRI", "SQUADRON")),
            true,
            null,
            null,
            8.0,
            Instant.now(),
            "Trade for **aUEC**.",
            0,
            List.of(),
            false,
            "ACTIVE",
            0L);
    when(backendApiClient.get(contains("/material-exchange/offers?"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(stockBacked), 0, 200, 1, 1, List.of()));
    when(backendApiClient.get(contains("/material-exchange/counts"), anyClass()))
        .thenReturn(new MaterialExchangeCountsDto(1, 1));
    when(backendApiClient.get(contains("/material-exchange/offers/"), anyClass()))
        .thenReturn(stockBacked);

    mockMvc
        .perform(get("/materialboerse").param("lang", "en"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-mb-edit")))
        .andExpect(content().string(containsString("data-kind=\"ITEM\"")))
        .andExpect(content().string(containsString("data-quantity-type=\"PIECE\"")))
        .andExpect(content().string(containsString("Quantum Drive")));
  }

  /** The list fragment renders on its own for an in-place filter swap. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void listFragment_rendersRows() throws Exception {
    stubBoard();

    mockMvc
        .perform(get("/materialboerse").param("fragment", "list").param("tab", "alle"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("mb-mrow")))
        .andExpect(content().string(containsString("Agricium")));
  }

  /** The deactivate proxy returns 200 on a successful backend call. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void deactivateProxy_returns200() throws Exception {
    when(backendApiClient.post(contains("/deactivate"), any(), eq(Object.class)))
        .thenReturn(Map.of("id", offerId.toString()));

    mockMvc
        .perform(post("/materialboerse/offers/" + offerId + "/deactivate/ajax").with(csrf()))
        .andExpect(status().isOk());
  }

  /**
   * The release-picker item search passes a multi-word {@code q} to the backend as a single-encoded
   * URI variable ({@code ?q={q}}) rather than pre-encoding it into the URI string. #1344
   * regression: the pre-encoded {@link
   * org.springframework.web.util.UriComponentsBuilder#toUriString()} value was re-encoded by the
   * WebClient (space &rarr; {@code %2520}), so a game-item stock row named "E2E Boerse Item Stock
   * Widget" matched nothing in the release picker.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void releasableItemsProxy_passesMultiWordQueryAsUriVariable() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/material-exchange/releasable-items?q={q}"),
            anyTypeRef(),
            eq("E2E Boerse Item Stock Widget")))
        .thenReturn(List.of());

    mockMvc
        .perform(get("/materialboerse/releasable-items").param("q", "E2E Boerse Item Stock Widget"))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .get(
            eq("/api/v1/material-exchange/releasable-items?q={q}"),
            anyTypeRef(),
            eq("E2E Boerse Item Stock Widget"));
  }

  /**
   * The release-picker forwards the Material/Item radio's {@code kind} to the backend as a safe,
   * pre-encoded query parameter alongside the single-encoded {@code q} URI variable
   * (REQ-MARKET-002). {@code kind} is a fixed enum token, so it rides on the base URI ({@code
   * ?kind=ITEM&q={q}}) without the double-encoding guard the free-text {@code q} needs.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void releasableItemsProxy_forwardsKindFilter() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/material-exchange/releasable-items?kind=ITEM&q={q}"),
            anyTypeRef(),
            eq("widget")))
        .thenReturn(List.of());

    mockMvc
        .perform(get("/materialboerse/releasable-items").param("q", "widget").param("kind", "ITEM"))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .get(
            eq("/api/v1/material-exchange/releasable-items?kind=ITEM&q={q}"),
            anyTypeRef(),
            eq("widget"));
  }

  /**
   * The remark-edit proxy relays a backend optimistic-lock conflict as 409 with the problem code.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void remarkProxy_backendConflict_relays409() throws Exception {
    when(backendApiClient.put(contains("/remark"), any(), eq(Object.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "conflict"));

    mockMvc
        .perform(
            put("/materialboerse/offers/" + offerId + "/remark/ajax")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType("application/json")
                .content("{\"offeredAmount\":120,\"remark\":\"neu\",\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(content().string(containsString("OPTIMISTIC_LOCK")));
  }
}
