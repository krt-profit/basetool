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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.SquadronContextAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the #575 no-reload AJAX twins on {@link RefineryOrderWriteController}: update /
 * store / cancel, each routed by the {@code X-Requested-With} header so the classic form-POST
 * handlers stay the no-JS fallback. Verifies the navigation-target JSON on success and the 400 on a
 * validation/empty-goods failure.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefineryOrderPageControllerNoReloadMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(
            "/api/v1/me/capabilities", SquadronContextAdvice.CapabilitiesResponse.class))
        .thenReturn(new SquadronContextAdvice.CapabilitiesResponse(true, true));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateOrderAjax_ValidForm_ReturnsListTarget() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/refinery-orders/" + id)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("goods[0].inputMaterialId", UUID.randomUUID().toString())
                .param("goods[0].inputQuantity", "100")
                .param("goods[0].outputQuantity", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetUrl").value("/refinery-orders"));

    verify(backendApiClient)
        .put(eq("/api/v1/refinery-orders/" + id), any(), eq(RefineryOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void updateOrderAjax_EmptyGoods_Returns400WithoutCallingBackend() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/refinery-orders/" + id)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isBadRequest());

    verify(backendApiClient, never())
        .put(eq("/api/v1/refinery-orders/" + id), any(), eq(RefineryOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void storeOrderAjax_EmptyItems_Returns400() throws Exception {
    UUID id = UUID.randomUUID();

    // RefineryOrderStoreForm.items is @NotEmpty, so a no-items submit fails validation -> 400.
    mockMvc
        .perform(
            post("/refinery-orders/" + id + "/store")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void createOrderAjax_ValidForm_ReturnsListTarget() throws Exception {
    mockMvc
        .perform(
            post("/refinery-orders/create")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("goods[0].inputMaterialId", UUID.randomUUID().toString())
                .param("goods[0].inputQuantity", "100")
                .param("goods[0].outputQuantity", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetUrl").value("/refinery-orders"));

    verify(backendApiClient).post(eq("/api/v1/refinery-orders"), any(), eq(RefineryOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void createOrderAjax_EmptyGoods_Returns400WithoutCallingBackend() throws Exception {
    mockMvc
        .perform(
            post("/refinery-orders/create")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isBadRequest());

    verify(backendApiClient, never())
        .post(eq("/api/v1/refinery-orders"), any(), eq(RefineryOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void deleteOrderAjax_ReturnsListTarget() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/refinery-orders/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetUrl").value("/refinery-orders"));

    verify(backendApiClient).delete(eq("/api/v1/refinery-orders/" + id), eq(Void.class));
  }
}
