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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies over HTTP that the production book-in refuses with 403 an owner whose inventory the
 * caller may not write, even when the caller may edit the order (REQ-INV-032).
 */
@SpringBootTest
class JobOrderProductionBookInSecurityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private OwnerScopeService ownerScopeService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * A production payload that passes bean validation, naming {@code ownerUserId} as the book-in
   * owner, so the request reaches the service rather than stopping at a 400.
   *
   * @param ownerUserId the member whose ledger the produced stock would land in
   * @return the JSON request body
   */
  private static String productionBody(UUID ownerUserId) {
    return "{\"amount\":1,\"version\":0,\"consumption\":[],\"bookIn\":{\"locationId\":\""
        + UUID.randomUUID()
        + "\",\"ownerUserId\":\""
        + ownerUserId
        + "\"}}";
  }

  @Test
  void bookProduction_ownerOutsideCallersScope_isForbidden() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID callerId = UUID.randomUUID();
    UUID foreignOwnerId = UUID.randomUUID();
    when(ownerScopeService.canEditJobOrder(any())).thenReturn(true);
    when(ownerScopeService.canManageUserInventory(foreignOwnerId)).thenReturn(false);

    mockMvc
        .perform(
            post("/api/v1/orders/{id}/items/{itemId}/production", orderId, UUID.randomUUID())
                .with(
                    jwt()
                        .jwt(token -> token.subject(callerId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productionBody(foreignOwnerId)))
        .andExpect(status().isForbidden());

    verify(ownerScopeService).canManageUserInventory(foreignOwnerId);
  }
}
