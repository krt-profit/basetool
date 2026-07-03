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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.SquadronContextAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the order-overview list (Auftragsverwaltung) renders an ITEM order's Materialien column
 * as its aggregated material list with collection progress ({@code currentStock / totalQuantity}),
 * mirroring MATERIAL orders, rather than the ordered items and their delivery count (#595). Renders
 * through the real Thymeleaf template so a broken expression fails the build, not only at runtime.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderListRenderTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // The default @WithMockUser is a non-admin, so the orders view's profit gate would otherwise
    // redirect to /orders/create. Stub the capability as a profit-eligible viewer so the list path
    // renders.
    when(backendApiClient.get(
            "/api/v1/me/capabilities", SquadronContextAdvice.CapabilitiesResponse.class))
        .thenReturn(new SquadronContextAdvice.CapabilitiesResponse(true, true));
  }

  private MaterialDto material(String name, String quantityType) {
    return new MaterialDto(
        UUID.randomUUID(),
        name,
        null,
        quantityType,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        1L);
  }

  @Test
  @WithMockUser
  void viewOrders_ItemOrder_RendersAggregatedMaterialsWithCollectionProgress() throws Exception {
    // Given: an ITEM order whose aggregated material (10 SCU required, 4 SCU gathered = 40 %) must
    // surface in the Materialien column; the ordered item carries a distinctive name that must NOT
    // appear, proving the column no longer renders items.
    UUID orderId = UUID.randomUUID();
    MaterialDto quantanium = material("Quantanium", "SCU");

    JobOrderItemDto item =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "ZZZ Unrendered Item Name", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "ZZZ Unrendered Item Name", "wiki-zzz"),
            2,
            0,
            null,
            List.of(),
            1L);

    JobOrderDto order =
        new JobOrderDto(
            orderId,
            42,
            null,
            null,
            "Tester",
            null,
            1,
            "OPEN",
            "ITEM",
            true,
            List.of(),
            List.of(item),
            List.of(new AggregatedMaterialDto(quantanium, "NONE", 10.0, 4.0, List.of(), null)),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L);

    when(backendApiClient.get(
            eq("/api/v1/orders?page=0&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS"),
            anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(order), 0, 1000, 1L, 1, List.of()));

    // When
    MvcResult result = mockMvc.perform(get("/orders")).andExpect(status().isOk()).andReturn();

    // Then
    String html = result.getResponse().getContentAsString();
    assertThat(html).contains("Quantanium");
    assertThat(html).contains("40 %");
    assertThat(html)
        .as("the overview no longer renders the ordered items in the Materialien column")
        .doesNotContain("ZZZ Unrendered Item Name");
  }
}
