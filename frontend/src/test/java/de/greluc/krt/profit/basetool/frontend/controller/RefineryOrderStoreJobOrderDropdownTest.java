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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Render test for the refinery store ("Einlagern") dialog's per-item "Auftrag" dropdown
 * (REQ-ORDERS-018). The dropdown offers only job orders whose requirements include the stored
 * output material. This pins the reported regression: the picker keyed on {@code
 * JobOrderDto.materials}, which is always empty for an {@code ITEM} order (its requirements live in
 * derived item materials, not {@code job_order_material} rows), so every {@code ITEM} order was
 * silently dropped and the dropdown stayed empty even when a matching order existed. The fix feeds
 * the picker from the {@code /api/v1/orders/lookup} reference projection and filters on the
 * kind-agnostic {@code requiredMaterialIds}. Stubs two ITEM orders sharing the lookup: one that
 * requires the good's output material (must render) and one that does not (must be hidden).
 */
@SpringBootTest
@ActiveProfiles("test")
class RefineryOrderStoreJobOrderDropdownTest {

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
  void storeDialog_OffersOnlyJobOrdersRequiringTheOutputMaterial_IncludingItemOrders()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID outputMaterialId = UUID.randomUUID();
    UUID matchingOrderId = UUID.randomUUID();
    UUID unrelatedOrderId = UUID.randomUUID();

    MaterialDto outputMaterial =
        new MaterialDto(
            outputMaterialId,
            "Quantanium",
            "REFINED",
            "SCU",
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            1L);
    RefineryGoodDto good =
        new RefineryGoodDto(UUID.randomUUID(), null, 100, outputMaterial, 50, 90, null);
    RefineryOrderDto order =
        new RefineryOrderDto(
            orderId,
            new UserReferenceDto(ownerId, "Owner", null, null, null),
            null,
            null,
            Instant.now(),
            60L,
            100.0,
            0d,
            0d,
            0d,
            null,
            List.of(good),
            RefineryOrderStatus.OPEN,
            null,
            1L,
            null);

    // Both are ITEM orders: empty MATERIAL-lines list, distinguished only by requiredMaterialIds —
    // exactly the case the old materials-based filter could not handle.
    JobOrderReferenceDto matching =
        new JobOrderReferenceDto(
            matchingOrderId, 71, "h1", "IN_PROGRESS", null, List.of(), List.of(outputMaterialId));
    JobOrderReferenceDto unrelated =
        new JobOrderReferenceDto(
            unrelatedOrderId, 99, "h2", "IN_PROGRESS", null, List.of(), List.of(UUID.randomUUID()));

    when(backendApiClient.get(eq("/api/v1/refinery-orders/" + orderId), eq(RefineryOrderDto.class)))
        .thenReturn(order);
    when(backendApiClient.get(
            eq("/api/v1/settings/refinery.rounding.mode"), eq(SystemSettingDto.class)))
        .thenReturn(new SystemSettingDto("refinery.rounding.mode", "UP", 1L));
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/orders/lookup")) {
                return List.of(matching, unrelated);
              }
              return null;
            });

    java.util.Map<String, Object> claims = new java.util.HashMap<>();
    claims.put(
        org.springframework.security.oauth2.core.oidc.IdTokenClaimNames.SUB, ownerId.toString());
    claims.put("preferred_username", "owner");
    org.springframework.security.oauth2.core.oidc.OidcIdToken idToken =
        new org.springframework.security.oauth2.core.oidc.OidcIdToken(
            "token-value", Instant.now(), Instant.now().plusSeconds(3600), claims);
    org.springframework.security.oauth2.core.oidc.user.OidcUser oidcUser =
        new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
            java.util.Collections.singletonList(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_KRT_MEMBER")),
            idToken);
    org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken authToken =
        new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
            oidcUser, oidcUser.getAuthorities(), "keycloak");

    String html =
        mockMvc
            .perform(
                get("/refinery-orders/" + orderId)
                    .locale(java.util.Locale.GERMAN)
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.authentication(authToken)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.junit.jupiter.api.Assertions.assertTrue(
        html.contains(matchingOrderId.toString()),
        "The ITEM order requiring the output material must be offered in the store dropdown");
    org.junit.jupiter.api.Assertions.assertFalse(
        html.contains(unrelatedOrderId.toString()),
        "An order that does not require the output material must be hidden from the dropdown");
  }
}
