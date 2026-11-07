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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShopAdminDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShopTerminalLinkRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShopTerminalOptionDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * The admin shops page over MVC (REQ-P4K-042, REQ-P4K-043): the page shell for an admin, the shop
 * list relayed with its filters, the terminal search, and the link change relayed to the backend
 * with a stale link passed through as {@code 409}.
 */
@SpringBootTest
class AdminShopsControllerMvcTest {

  private static final String BASE = "/api/v1/catalog/admin/shops";
  private static final UUID SHOP = UUID.fromString("00000000-0000-4000-8000-0000000000d1");
  private static final UUID TERMINAL = UUID.fromString("00000000-0000-4000-8000-0000000000d2");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * The Cubby Blast shop of Area 18.
   *
   * @param terminalId the linked terminal, or {@code null}
   * @param manual whether the link is manual
   * @return the shop
   */
  private static @NotNull ShopAdminDto cubbyBlast(@Nullable UUID terminalId, boolean manual) {
    return new ShopAdminDto(
        SHOP,
        "CubbyBlast_Area18",
        "CubbyBlast_Area18",
        "stanton",
        "PERSONAL_WEAPON",
        "cubbyblast",
        false,
        true,
        "Area18",
        terminalId,
        terminalId == null ? null : "Cubby Blast - Area 18",
        manual,
        false);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePage_carriesTheListTheDialogAndTheScript() throws Exception {
    String html =
        mockMvc
            .perform(get("/admin/shops").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains(
            "id=\"shops-rows\"",
            "id=\"shops-pager\"",
            "id=\"shops-unlinked\"",
            "id=\"shops-link-modal\"",
            "/js/catalogue/shops.js",
            "window.krtShopsI18n",
            "Handwaffen",
            "</html>");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMember_isRefused() throws Exception {
    mockMvc.perform(get("/admin/shops")).andExpect(status().isForbidden());
    mockMvc.perform(get("/admin/shops/entries")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theShopList_relaysOnlyTheFiltersGiven() throws Exception {
    PageResponse<ShopAdminDto> page =
        new PageResponse<>(List.of(cubbyBlast(null, false)), 0, 25, 1, 1, List.of());
    when(backendApiClient.get(
            eq(BASE + "?page={page}&size={size}&unlinkedOnly={unlinkedOnly}"),
            anyTypeRef(),
            eq(0),
            eq(25),
            eq(false)))
        .thenReturn(page);
    when(backendApiClient.get(
            eq(
                BASE
                    + "?page={page}&size={size}&unlinkedOnly={unlinkedOnly}"
                    + "&starSystem={starSystem}&query={query}"),
            anyTypeRef(),
            eq(1),
            eq(25),
            eq(true),
            eq("stanton"),
            eq("Cubby")))
        .thenReturn(page);

    mockMvc
        .perform(get("/admin/shops/entries").param("starSystem", " ").param("query", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].registryName").value("CubbyBlast_Area18"))
        .andExpect(jsonPath("$.content[0].itemListKnown").value(true));
    mockMvc
        .perform(
            get("/admin/shops/entries")
                .param("starSystem", "stanton")
                .param("query", " Cubby ")
                .param("unlinkedOnly", "true")
                .param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].kind").value("PERSONAL_WEAPON"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theTerminalSearch_relaysTheFragment_andABlankOneAsksNothing() throws Exception {
    when(backendApiClient.get(eq(BASE + "/terminals?query={query}"), anyTypeRef(), eq("Cubby")))
        .thenReturn(
            List.of(new ShopTerminalOptionDto(TERMINAL, "Cubby Blast - Area 18", "Stanton")));

    mockMvc
        .perform(get("/admin/shops/terminals").param("query", " Cubby "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Cubby Blast - Area 18"));
    mockMvc
        .perform(get("/admin/shops/terminals").param("query", " "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aLinkChange_returnsTheShop_andAChangedLinkIsAConflict() throws Exception {
    String link = BASE + "/{id}/terminal-link";
    when(backendApiClient.put(
            link,
            new ShopTerminalLinkRequest("SET", TERMINAL, null, false),
            ShopAdminDto.class,
            SHOP))
        .thenReturn(cubbyBlast(TERMINAL, true));
    when(backendApiClient.put(
            link,
            new ShopTerminalLinkRequest("RELEASE", null, TERMINAL, true),
            ShopAdminDto.class,
            SHOP))
        .thenThrow(new BackendServiceException("changed", null, 409));

    mockMvc
        .perform(
            post("/admin/shops/" + SHOP + "/terminal-link")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"action\":\"SET\",\"terminalId\":\""
                        + TERMINAL
                        + "\",\"expectedTerminalId\":null,\"expectedManual\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.manual").value(true))
        .andExpect(jsonPath("$.terminalName").value("Cubby Blast - Area 18"));
    mockMvc
        .perform(
            post("/admin/shops/" + SHOP + "/terminal-link")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"action\":\"RELEASE\",\"expectedTerminalId\":\""
                        + TERMINAL
                        + "\",\"expectedManual\":true}"))
        .andExpect(status().isConflict());
  }
}
