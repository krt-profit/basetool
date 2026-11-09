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

package de.greluc.krt.profit.basetool.frontend.refinery.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderDto;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The presence read the refinery-order page asks before it acts on a {@code deleted} signal
 * (REQ-XCH-055): 204 while the caller can read the order, the backend's 404 or 403 once it is gone.
 */
@SpringBootTest
class RefineryOrderPresenceControllerTest {

  private static final UUID ORDER_ID = UUID.randomUUID();

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void aReadableOrderAnswersNoContent() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/refinery-orders/{id}"), eq(RefineryOrderDto.class), eq(ORDER_ID)))
        .thenReturn(null);

    mockMvc.perform(presence()).andExpect(status().isNoContent());
  }

  @Test
  void aDeletedOrderAnswersTheBackendsNotFound() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/refinery-orders/{id}"), eq(RefineryOrderDto.class), eq(ORDER_ID)))
        .thenThrow(backendFailure(404));

    mockMvc.perform(presence()).andExpect(status().isNotFound());
  }

  @Test
  void anOrderTheCallerMayNoLongerSeeAnswersTheBackendsForbidden() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/refinery-orders/{id}"), eq(RefineryOrderDto.class), eq(ORDER_ID)))
        .thenThrow(backendFailure(403));

    mockMvc.perform(presence()).andExpect(status().isForbidden());
  }

  private static BackendServiceException backendFailure(int status) {
    return new BackendServiceException("refused", null, status);
  }

  private MockHttpServletRequestBuilder presence() {
    return get("/refinery-orders/" + ORDER_ID + "/presence")
        .header("X-Requested-With", "XMLHttpRequest")
        .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
  }
}
