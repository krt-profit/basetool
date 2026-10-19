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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderCreateRequest;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderDto;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Tests how the create form's goods reach the backend, per REQ-REFINERY-025. */
@SpringBootTest
class RefineryOrderWriteControllerGoodsTest {

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

  private static MockHttpServletRequestBuilder createWith(String inputQuantity, String output) {
    return post("/refinery-orders/create")
        .param("durationHours", "1")
        .param("durationMinutes", "0")
        .param("ownerId", UUID.randomUUID().toString())
        .param("locationId", UUID.randomUUID().toString())
        .param("startedAt", "2026-10-19T12:00")
        .param("goods[0].inputMaterialId", UUID.randomUUID().toString())
        .param("goods[0].quality", "500")
        .param("goods[0].inputQuantity", inputQuantity)
        .param("goods[0].outputQuantity", output)
        .with(csrf())
        .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
  }

  private RefineryGoodDto sentGood() {
    ArgumentCaptor<RefineryOrderCreateRequest> captor =
        ArgumentCaptor.forClass(RefineryOrderCreateRequest.class);
    verify(backendApiClient)
        .post(eq("/api/v1/refinery-orders"), captor.capture(), eq(RefineryOrderDto.class));
    assertEquals(1, captor.getValue().goods().size());
    return captor.getValue().goods().getFirst();
  }

  @Test
  void emptyInputQuantity_isSentAsZero() throws Exception {
    mockMvc.perform(createWith("", "12")).andExpect(status().is3xxRedirection());

    RefineryGoodDto good = sentGood();
    assertEquals(0, good.inputQuantity());
    assertEquals(12, good.outputQuantity());
  }

  @Test
  void enteredInputQuantity_isSentUnchanged() throws Exception {
    mockMvc.perform(createWith("40", "12")).andExpect(status().is3xxRedirection());

    assertEquals(40, sentGood().inputQuantity());
  }

  @Test
  void emptyOutputQuantity_isRejectedWithoutBackendCall() throws Exception {
    mockMvc.perform(createWith("40", ""));

    verify(backendApiClient, never())
        .post(eq("/api/v1/refinery-orders"), ArgumentMatchers.any(), eq(RefineryOrderDto.class));
  }
}
