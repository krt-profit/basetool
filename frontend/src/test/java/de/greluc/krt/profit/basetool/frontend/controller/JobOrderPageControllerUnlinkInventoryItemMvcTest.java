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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
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

/**
 * MVC-Tests fuer den unlinkInventoryItem-POST-Pfad in {@link
 * JobOrderWriteController#unlinkInventoryItem}.
 *
 * <p>Testet, dass Logistiker/Officer/Admin einen einzelnen Lagereintrag aus einem Auftrag entlinken
 * koennen:
 *
 * <ul>
 *   <li>Logistiker kann Lagereintrag entlinken (success-Toast + Redirect).
 *   <li>Einfacher Member ohne Logistiker-Rechte erhaelt 403 Forbidden.
 *   <li>Backend-Fehler bei Logistiker → error-Toast + Redirect.
 * </ul>
 */
@SpringBootTest
class JobOrderPageControllerUnlinkInventoryItemMvcTest {

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

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void unlinkInventoryItem_AsLogistician_ShouldCallBackendAndRedirectWithSuccessToast()
      throws Exception {
    // Given
    UUID orderId = UUID.randomUUID();
    UUID inventoryItemId = UUID.randomUUID();

    when(backendApiClient.delete(
            eq("/api/v1/orders/" + orderId + "/inventory/" + inventoryItemId + "/unlink"),
            eq(Void.class)))
        .thenReturn(null);

    // When
    mockMvc
        .perform(
            post("/orders/" + orderId + "/inventory/" + inventoryItemId + "/unlink").with(csrf()))
        // Then
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/orders/" + orderId))
        .andExpect(flash().attribute("successToast", "orders.detail.inventory.unlink.success"));

    verify(backendApiClient)
        .delete(
            eq("/api/v1/orders/" + orderId + "/inventory/" + inventoryItemId + "/unlink"),
            eq(Void.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER"})
  void unlinkInventoryItem_AsPlainMember_ShouldReturn403() throws Exception {
    // Given
    UUID orderId = UUID.randomUUID();
    UUID inventoryItemId = UUID.randomUUID();

    // When / Then
    mockMvc
        .perform(
            post("/orders/" + orderId + "/inventory/" + inventoryItemId + "/unlink").with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void unlinkInventoryItem_WhenBackendFails_ShouldRedirectWithErrorToast() throws Exception {
    // Given
    UUID orderId = UUID.randomUUID();
    UUID inventoryItemId = UUID.randomUUID();

    doThrow(new BackendServiceException("Internal Server Error", null, 500))
        .when(backendApiClient)
        .delete(
            eq("/api/v1/orders/" + orderId + "/inventory/" + inventoryItemId + "/unlink"),
            eq(Void.class));

    // When
    mockMvc
        .perform(
            post("/orders/" + orderId + "/inventory/" + inventoryItemId + "/unlink").with(csrf()))
        // Then
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/orders/" + orderId))
        .andExpect(flash().attribute("errorToast", "orders.detail.inventory.unlink.error"));
  }
}
