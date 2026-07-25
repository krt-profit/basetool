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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.CapabilityFlagsAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStoreDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the personal marker in the refinery-order store dialog (REQ-INV-035): refinery
 * output can be booked straight into the receiver's private pool instead of the shared squadron
 * stock, so a member no longer has to store it shared and rebook it on {@code /inventory/my}
 * afterwards.
 *
 * <p>Covers the three seams the feature adds on the frontend side: the per-row checkbox rendered by
 * {@code refinery-orders-details.html} and bound to {@code items[i].personal}, the forwarding of
 * that flag into the backend store payload, and the cross-field guard that rejects the
 * contradictory "personal + job order" combination before the backend call (personal stock never
 * carries an allocation) — with its own toast on the classic form path and a 400 on the AJAX twin.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefineryStorePersonalMarkerTest {

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
            "/api/v1/me/capabilities", CapabilityFlagsAdvice.CapabilitiesResponse.class))
        .thenReturn(new CapabilityFlagsAdvice.CapabilitiesResponse(true, true, true));
  }

  /**
   * Builds an authenticated logistician principal.
   *
   * @param userId the Keycloak subject to authenticate as
   * @return the OAuth2 token carrying {@code ROLE_LOGISTICIAN}
   */
  private OAuth2AuthenticationToken logisticianToken(UUID userId) {
    Map<String, Object> claims = new HashMap<>();
    claims.put(IdTokenClaimNames.SUB, userId.toString());
    claims.put("preferred_username", "logistician");
    OidcIdToken idToken =
        new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600), claims);
    OidcUser oidcUser =
        new DefaultOidcUser(
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")), idToken);
    return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");
  }

  /**
   * Builds a minimal material reference for a refinery good.
   *
   * @param name the material name rendered in the store row heading
   * @return the material DTO
   */
  private MaterialDto material(String name) {
    return new MaterialDto(
        UUID.randomUUID(),
        name,
        null,
        "SCU",
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
  void storeDialog_RendersAPersonalCheckboxBoundToEachOutputRow() throws Exception {
    // Given: an order with a single output good, so the store dialog builds exactly one row.
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    RefineryGoodDto good =
        new RefineryGoodDto(
            UUID.randomUUID(),
            material("Quantanium Ore"),
            100,
            material("Refined Quantanium"),
            100,
            100,
            null);
    RefineryOrderDto order =
        new RefineryOrderDto(
            orderId,
            new UserReferenceDto(userId, "logistician", null, "Logistician", 0),
            new LocationDto(UUID.randomUUID(), "ArcCorp Mining", null, false, false, 1L),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(good),
            RefineryOrderStatus.OPEN,
            null,
            1L,
            null);
    when(backendApiClient.get(eq("/api/v1/refinery-orders/" + orderId), eq(RefineryOrderDto.class)))
        .thenReturn(order);

    // When
    String html =
        mockMvc
            .perform(
                get("/refinery-orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Then: the row carries the checkbox, bound to the indexed form path so a split row can be
    // reindexed by refinery-orders-details.js, plus Spring's hidden marker that makes an unticked
    // box arrive as false.
    assertThat(html).as("personal checkbox rendered").contains("id=\"storePersonal_0\"");
    assertThat(html).as("bound to the indexed form path").contains("name=\"items[0].personal\"");
    assertThat(html)
        .as("hidden checkbox marker present so unticked binds to false")
        .contains("name=\"_items[0].personal\"");
  }

  @Test
  void storeOrderAjax_ForwardsThePersonalMarkerToTheBackendPayload() throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/refinery-orders/" + orderId + "/store")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(authentication(logisticianToken(UUID.randomUUID())))
                .with(csrf())
                .param("items[0].materialId", UUID.randomUUID().toString())
                .param("items[0].locationId", UUID.randomUUID().toString())
                .param("items[0].quality", "500")
                .param("items[0].amount", "12.5")
                .param("items[0].personal", "true"))
        .andExpect(status().isOk());

    ArgumentCaptor<Object> payload = ArgumentCaptor.captor();
    verify(backendApiClient)
        .post(
            eq("/api/v1/refinery-orders/" + orderId + "/store"), payload.capture(), eq(Void.class));
    assertThat(payload.getValue()).isInstanceOf(RefineryOrderStoreDto.class);
    RefineryOrderStoreDto dto = (RefineryOrderStoreDto) payload.getValue();
    assertThat(dto.items()).hasSize(1);
    assertThat(dto.items().get(0).personal()).isTrue();
  }

  @Test
  void storeOrderAjax_PersonalCombinedWithAJobOrder_Returns400WithoutCallingTheBackend()
      throws Exception {
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/refinery-orders/" + orderId + "/store")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(authentication(logisticianToken(UUID.randomUUID())))
                .with(csrf())
                .param("items[0].materialId", UUID.randomUUID().toString())
                .param("items[0].locationId", UUID.randomUUID().toString())
                .param("items[0].quality", "500")
                .param("items[0].amount", "12.5")
                .param("items[0].personal", "true")
                .param("items[0].jobOrderId", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest());

    verify(backendApiClient, never())
        .post(eq("/api/v1/refinery-orders/" + orderId + "/store"), any(), eq(Void.class));
  }

  @Test
  void storeOrder_PersonalCombinedWithAJobOrder_FlashesTheDedicatedToastAndReopensTheModal()
      throws Exception {
    // The no-JS fallback must name the actual reason instead of the generic store-failed toast.
    UUID orderId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/refinery-orders/" + orderId + "/store")
                .with(authentication(logisticianToken(UUID.randomUUID())))
                .with(csrf())
                .param("items[0].materialId", UUID.randomUUID().toString())
                .param("items[0].locationId", UUID.randomUUID().toString())
                .param("items[0].quality", "500")
                .param("items[0].amount", "12.5")
                .param("items[0].personal", "true")
                .param("items[0].jobOrderId", UUID.randomUUID().toString()))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorToast", "error.refineryorder.store.personal.assignment"))
        .andExpect(flash().attribute("showStoreModal", true));

    verify(backendApiClient, never())
        .post(eq("/api/v1/refinery-orders/" + orderId + "/store"), any(), eq(Void.class));
  }
}
