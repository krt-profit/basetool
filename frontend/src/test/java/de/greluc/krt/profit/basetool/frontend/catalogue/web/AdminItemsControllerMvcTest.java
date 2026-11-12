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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.GameItemAdminDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.GameItemObtainabilityRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
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
 * The admin item page over MVC (REQ-P4K-048): the page shell for an admin, the list relayed with
 * its search and filter, a hide relayed to the backend with the item-order probe evicted, a running
 * sync passed through as {@code 409}, and every other role refused.
 */
@SpringBootTest
class AdminItemsControllerMvcTest {

  private static final String BASE = "/api/v1/catalog/admin/game-items";
  private static final String LIST =
      BASE + "?page={page}&size={size}&unobtainableOnly={unobtainableOnly}";
  private static final String OBTAINABILITY = BASE + "/{id}/obtainability";
  private static final UUID ITEM = UUID.fromString("00000000-0000-4000-8000-0000000000d1");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * A railgun of a stocked type.
   *
   * @param unobtainableSince when an admin hid it, or {@code null}
   * @return the row
   */
  private static @NotNull GameItemAdminDto railgun(@Nullable String unobtainableSince) {
    return new GameItemAdminDto(
        ITEM,
        "Scourge Railgun",
        null,
        "WeaponPersonal",
        "Large",
        "Apocalypse Arms",
        false,
        unobtainableSince);
  }

  private static <T> PageResponse<T> page(List<T> rows, int page) {
    return new PageResponse<>(rows, page, 25, rows.size(), 1, List.of());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePage_carriesTheListItsFilterAndTheScript() throws Exception {
    String html =
        mockMvc
            .perform(get("/admin/items").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains(
            "id=\"admin-items-rows\"",
            "id=\"admin-items-query\"",
            "id=\"admin-items-unobtainable\"",
            "id=\"admin-items-pager\"",
            "id=\"admin-items-empty\"",
            "/js/catalogue/admin-items.js",
            "window.krtAdminItemsI18n",
            "Gegenstände",
            "Nur nicht erhältliche",
            "</html>");
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void anyOtherRole_isRefused() throws Exception {
    mockMvc.perform(get("/admin/items")).andExpect(status().isForbidden());
    mockMvc.perform(get("/admin/items/list")).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/admin/items/" + ITEM + "/obtainability")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"obtainable\":false}"))
        .andExpect(status().isForbidden());
    verify(backendApiClient, never()).put(any(), any(), any(), any(Object[].class));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theList_relaysTheSearchOnlyWhenGiven_andTheFilter() throws Exception {
    when(backendApiClient.get(eq(LIST), anyTypeRef(), eq(0), eq(25), eq(false)))
        .thenReturn(page(List.of(railgun(null)), 0));
    when(backendApiClient.get(
            eq(LIST + "&query={query}"), anyTypeRef(), eq(1), eq(25), eq(true), eq("Rail")))
        .thenReturn(page(List.of(railgun("2026-11-12T10:00:00Z")), 1));

    mockMvc
        .perform(get("/admin/items/list").param("query", " "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Scourge Railgun"))
        .andExpect(jsonPath("$.content[0].unobtainableSince").doesNotExist());
    mockMvc
        .perform(
            get("/admin/items/list")
                .param("query", " Rail ")
                .param("unobtainableOnly", "true")
                .param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.content[0].unobtainableSince").value("2026-11-12T10:00:00Z"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aHide_returnsTheItemAndEvictsTheOrderProbe_andARunningSyncIsAConflict() throws Exception {
    when(backendApiClient.put(
            OBTAINABILITY,
            new GameItemObtainabilityRequest(false),
            GameItemAdminDto.class,
            ITEM))
        .thenReturn(railgun("2026-11-12T10:00:00Z"));
    when(backendApiClient.put(
            OBTAINABILITY, new GameItemObtainabilityRequest(true), GameItemAdminDto.class, ITEM))
        .thenThrow(new BackendServiceException("A sync is running", null, 409));

    mockMvc
        .perform(
            post("/admin/items/" + ITEM + "/obtainability")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"obtainable\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ITEM.toString()))
        .andExpect(jsonPath("$.unobtainableSince").value("2026-11-12T10:00:00Z"));
    verify(backendApiClient).evict(CacheDomain.ITEM_CATALOG);
    mockMvc
        .perform(
            post("/admin/items/" + ITEM + "/obtainability")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"obtainable\":true}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aChangeWithoutAState_isRefusedBeforeTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/admin/items/" + ITEM + "/obtainability")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    verify(backendApiClient, never()).put(any(), any(), any(), any(Object[].class));
  }
}
