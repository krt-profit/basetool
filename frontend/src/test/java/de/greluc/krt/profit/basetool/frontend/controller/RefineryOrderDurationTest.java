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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class RefineryOrderDurationTest {

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
  void testCreateOrder_DurationConversion() throws Exception {
    mockMvc
        .perform(
            post("/refinery-orders/create")
                .param("durationHours", "2")
                .param("durationMinutes", "15")
                .param("expenses", "100")
                .param("ownerId", UUID.randomUUID().toString())
                .param("locationId", UUID.randomUUID().toString())
                .param("refiningMethodId", UUID.randomUUID().toString())
                .param("startedAt", "2024-04-06T12:00")
                .param("goods[0].inputMaterialId", UUID.randomUUID().toString())
                .param("goods[0].inputQuantity", "100")
                .param("goods[0].outputQuantity", "90")
                .with(csrf())
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"))))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            header().string("Location", org.hamcrest.Matchers.containsString("/refinery-orders")))
        .andExpect(
            header()
                .string(
                    "Location",
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/create"))));

    ArgumentCaptor<RefineryOrderDto> captor = ArgumentCaptor.forClass(RefineryOrderDto.class);
    verify(backendApiClient)
        .post(eq("/api/v1/refinery-orders"), captor.capture(), eq(RefineryOrderDto.class));

    assertEquals(135, captor.getValue().durationMinutes());
  }

  @Test
  void testViewOrder_DurationBackConversion() throws Exception {
    UUID orderId = UUID.randomUUID();
    RefineryOrderDto order =
        new RefineryOrderDto(
            orderId,
            null,
            null,
            null,
            java.time.Instant.now(),
            145L,
            100.0,
            0d,
            0d,
            0d,
            null,
            Collections.emptyList(),
            null,
            null,
            1L,
            null);
    when(backendApiClient.get(eq("/api/v1/refinery-orders/" + orderId), eq(RefineryOrderDto.class)))
        .thenReturn(order);

    mockMvc
        .perform(
            get("/refinery-orders/" + orderId)
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"))))
        .andExpect(status().isOk())
        .andExpect(model().attributeExists("refineryOrderForm"))
        .andExpect(
            model()
                .attribute(
                    "refineryOrderForm",
                    org.hamcrest.Matchers.hasProperty(
                        "durationHours", org.hamcrest.Matchers.is(2))))
        .andExpect(
            model()
                .attribute(
                    "refineryOrderForm",
                    org.hamcrest.Matchers.hasProperty(
                        "durationMinutes", org.hamcrest.Matchers.is(25))));
  }

  @Test
  void testUpdateOrder_DurationConversion() throws Exception {
    UUID orderId = UUID.randomUUID();
    mockMvc
        .perform(
            post("/refinery-orders/" + orderId)
                .param("durationHours", "1")
                .param("durationMinutes", "5")
                .param("expenses", "200")
                .param("version", "1")
                .param("ownerId", UUID.randomUUID().toString())
                .param("locationId", UUID.randomUUID().toString())
                .param("refiningMethodId", UUID.randomUUID().toString())
                .param("startedAt", "2024-04-06T12:00")
                .param("goods[0].inputMaterialId", UUID.randomUUID().toString())
                .param("goods[0].inputQuantity", "100")
                .param("goods[0].outputQuantity", "90")
                .with(csrf())
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"))))
        .andExpect(status().is3xxRedirection());

    ArgumentCaptor<RefineryOrderDto> captor = ArgumentCaptor.forClass(RefineryOrderDto.class);
    verify(backendApiClient)
        .put(
            eq("/api/v1/refinery-orders/" + orderId), captor.capture(), eq(RefineryOrderDto.class));

    assertEquals(65, captor.getValue().durationMinutes());
  }

  @Test
  void testEndsAtCalculation() {
    java.time.Instant startedAt = java.time.Instant.parse("2024-04-06T12:00:00Z");
    RefineryOrderDto order =
        new RefineryOrderDto(
            UUID.randomUUID(),
            null,
            null,
            null,
            startedAt,
            125L,
            100.0,
            0d,
            0d,
            0d,
            null,
            Collections.emptyList(),
            null,
            null,
            1L,
            null);

    java.time.Instant expectedEnd = startedAt.plus(125, java.time.temporal.ChronoUnit.MINUTES);
    assertEquals(expectedEnd, order.getEndsAt());
    assertNotNull(order.getEndsAt());
  }

  @Test
  void testViewOrders_OnlyMineFilter() throws Exception {
    mockMvc
        .perform(get("/refinery-orders").param("onlyMine", "true").with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("onlyMine", true));

    verify(backendApiClient)
        .get(
            org.mockito.ArgumentMatchers.contains("/api/v1/refinery-orders/my-orders"),
            anyTypeRef());
  }

  @Test
  void testViewOrders_AllOrders() throws Exception {
    mockMvc
        .perform(get("/refinery-orders").with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("onlyMine", false));

    verify(backendApiClient)
        .get(org.mockito.ArgumentMatchers.contains("/api/v1/refinery-orders/all"), anyTypeRef());
  }

  @Test
  void testViewCreateForm_DurationDefaultsZero() throws Exception {
    mockMvc
        .perform(get("/refinery-orders/create").with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(model().attributeExists("refineryOrderForm"))
        .andExpect(
            model()
                .attribute(
                    "refineryOrderForm",
                    org.hamcrest.Matchers.hasProperty(
                        "durationHours", org.hamcrest.Matchers.is(0))))
        .andExpect(
            model()
                .attribute(
                    "refineryOrderForm",
                    org.hamcrest.Matchers.hasProperty(
                        "durationMinutes", org.hamcrest.Matchers.is(0))));
  }

  @Test
  void testCreateOrder_RejectsNegativeHours() throws Exception {
    mockMvc
        .perform(
            post("/refinery-orders/create")
                .param("durationHours", "-1")
                .param("durationMinutes", "15")
                .param("expenses", "100")
                .param("ownerId", UUID.randomUUID().toString())
                .param("locationId", UUID.randomUUID().toString())
                .param("refiningMethodId", UUID.randomUUID().toString())
                .param("startedAt", "2024-04-06T12:00")
                .param("goods[0].inputMaterialId", UUID.randomUUID().toString())
                .param("goods[0].inputQuantity", "100")
                .param("goods[0].outputQuantity", "90")
                .with(csrf())
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"))))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            header()
                .string(
                    "Location", org.hamcrest.Matchers.containsString("/refinery-orders/create")));

    org.mockito.Mockito.verify(backendApiClient, org.mockito.Mockito.never())
        .post(eq("/api/v1/refinery-orders"), any(), eq(RefineryOrderDto.class));
  }

  @Test
  void testCreateOrder_RejectsMinutesOutOfRange() throws Exception {
    mockMvc
        .perform(
            post("/refinery-orders/create")
                .param("durationHours", "1")
                .param("durationMinutes", "60")
                .param("expenses", "100")
                .param("ownerId", UUID.randomUUID().toString())
                .param("locationId", UUID.randomUUID().toString())
                .param("refiningMethodId", UUID.randomUUID().toString())
                .param("startedAt", "2024-04-06T12:00")
                .param("goods[0].inputMaterialId", UUID.randomUUID().toString())
                .param("goods[0].inputQuantity", "100")
                .param("goods[0].outputQuantity", "90")
                .with(csrf())
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"))))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            header()
                .string(
                    "Location", org.hamcrest.Matchers.containsString("/refinery-orders/create")));

    org.mockito.Mockito.verify(backendApiClient, org.mockito.Mockito.never())
        .post(eq("/api/v1/refinery-orders"), any(), eq(RefineryOrderDto.class));
  }

  @Test
  void testCreateOrder_AcceptsZeroDuration() throws Exception {
    mockMvc
        .perform(
            post("/refinery-orders/create")
                .param("durationHours", "0")
                .param("durationMinutes", "0")
                .param("expenses", "100")
                .param("ownerId", UUID.randomUUID().toString())
                .param("locationId", UUID.randomUUID().toString())
                .param("refiningMethodId", UUID.randomUUID().toString())
                .param("startedAt", "2024-04-06T12:00")
                .param("goods[0].inputMaterialId", UUID.randomUUID().toString())
                .param("goods[0].inputQuantity", "100")
                .param("goods[0].outputQuantity", "90")
                .with(csrf())
                .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"))))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            header()
                .string(
                    "Location",
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/create"))));

    ArgumentCaptor<RefineryOrderDto> captor = ArgumentCaptor.forClass(RefineryOrderDto.class);
    verify(backendApiClient)
        .post(eq("/api/v1/refinery-orders"), captor.capture(), eq(RefineryOrderDto.class));
    assertEquals(0L, captor.getValue().durationMinutes());
  }
}
