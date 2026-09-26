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
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC-level test for {@link PersonalInventoryPageController}: verifies that the user-area personal
 * inventory page renders for an authenticated user with model attributes filled from the (mocked)
 * backend.
 */
@SpringBootTest
class PersonalInventoryPageControllerMvcTest {

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
  void view_shouldRenderPersonalInventoryView_whenAuthenticated() throws Exception {
    PageResponse<PersonalInventoryItemDto> empty =
        new PageResponse<>(List.of(), 0, 50, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(empty);

    mockMvc
        .perform(get("/personal-inventory"))
        .andExpect(status().isOk())
        .andExpect(view().name("personal-inventory"))
        .andExpect(model().attributeExists("personalInventoryForm"))
        .andExpect(model().attributeExists("items"));
  }

  @Test
  @WithMockUser
  void view_fragmentResults_rendersOnlyResultsFragment() throws Exception {
    PageResponse<PersonalInventoryItemDto> empty =
        new PageResponse<>(List.of(), 0, 50, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(empty);

    mockMvc
        .perform(get("/personal-inventory").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("personal-inventory :: results"))
        .andExpect(content().string(containsString("id=\"pi-total-meta\"")))
        .andExpect(content().string(containsString("empty-state")))
        .andExpect(content().string(not(containsString("id=\"pi-results\""))))
        .andExpect(content().string(not(containsString("krt-pi-filter"))))
        .andExpect(content().string(not(containsString("id=\"krt-pi-modal\""))));
  }

  @Test
  @WithMockUser
  void uexSearch_passesMultiWordQueryAsUriVariable() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(List.of());

    mockMvc
        .perform(get("/personal-inventory/uex-search").param("q", "Port Olisar"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals("Port Olisar", qCaptor.getValue());
  }

  @Test
  @WithMockUser
  void uexSearch_passesUmlautQueryAsUriVariable_notFormEncoded() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(List.of());

    String term = "Müller Hütte";
    mockMvc
        .perform(get("/personal-inventory/uex-search").param("q", term))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals(term, qCaptor.getValue());
  }
}
