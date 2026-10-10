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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.settings.model.SystemSettingDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class RefineryOrderHierarchyVisibilityTest {

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
  void refineryOrderDetail_AsOfficer_ShouldShowLogisticianButtons() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID officerUserId = UUID.randomUUID();

    RefineryOrderDto order =
        new RefineryOrderDto(
            orderId,
            new UserReferenceDto(ownerId, "Owner", null, null, null),
            null,
            null,
            java.time.Instant.now(),
            60L,
            100.0,
            0d,
            0d,
            0d,
            null,
            List.of(),
            RefineryOrderStatus.OPEN,
            null,
            1L,
            null);

    when(backendApiClient.get(
            eq("/api/v1/refinery-orders/{id}"), eq(RefineryOrderDto.class), eq(orderId)))
        .thenReturn(order);
    when(backendApiClient.get(
            eq("/api/v1/settings/refinery.rounding.mode"), eq(SystemSettingDto.class)))
        .thenReturn(new SystemSettingDto("refinery.rounding.mode", "UP", 1L));

    java.util.Map<String, Object> claims = new java.util.HashMap<>();
    claims.put(
        org.springframework.security.oauth2.core.oidc.IdTokenClaimNames.SUB,
        officerUserId.toString());
    claims.put("preferred_username", "officer");
    org.springframework.security.oauth2.core.oidc.OidcIdToken idToken =
        new org.springframework.security.oauth2.core.oidc.OidcIdToken(
            "token-value",
            java.time.Instant.now(),
            java.time.Instant.now().plusSeconds(3600),
            claims);
    org.springframework.security.oauth2.core.oidc.user.OidcUser oidcUser =
        new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
            java.util.Collections.singletonList(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_OFFICER")),
            idToken);
    org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken authToken =
        new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
            oidcUser, oidcUser.getAuthorities(), "keycloak");

    mockMvc
        .perform(
            get("/refinery-orders/" + orderId)
                .locale(java.util.Locale.GERMAN)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(authToken)))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Einlagern")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Auftrag Abbrechen")));
  }

  @Test
  void refineryOrderDetail_Completed_AsOwner_ShouldShowSaveButton_ButHideStoreAndCancel()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    RefineryOrderDto order =
        new RefineryOrderDto(
            orderId,
            new UserReferenceDto(ownerId, "Owner", null, null, null),
            null,
            null,
            java.time.Instant.now(),
            60L,
            100.0,
            0d,
            0d,
            0d,
            null,
            List.of(),
            RefineryOrderStatus.COMPLETED,
            null,
            1L,
            null);

    when(backendApiClient.get(
            eq("/api/v1/refinery-orders/{id}"), eq(RefineryOrderDto.class), eq(orderId)))
        .thenReturn(order);
    when(backendApiClient.get(
            eq("/api/v1/settings/refinery.rounding.mode"), eq(SystemSettingDto.class)))
        .thenReturn(new SystemSettingDto("refinery.rounding.mode", "UP", 1L));

    java.util.Map<String, Object> claims = new java.util.HashMap<>();
    claims.put(
        org.springframework.security.oauth2.core.oidc.IdTokenClaimNames.SUB, ownerId.toString());
    claims.put("preferred_username", "owner");
    org.springframework.security.oauth2.core.oidc.OidcIdToken idToken =
        new org.springframework.security.oauth2.core.oidc.OidcIdToken(
            "token-value",
            java.time.Instant.now(),
            java.time.Instant.now().plusSeconds(3600),
            claims);
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
        html.contains("Speichern"), "Save button should be visible in COMPLETED state for owner");
    org.junit.jupiter.api.Assertions.assertFalse(
        html.contains("onclick=\"openStoreModal()\""),
        "Store button should be hidden in COMPLETED state");
    org.junit.jupiter.api.Assertions.assertFalse(
        html.contains("id=\"refineryCancelForm\""),
        "Cancel button should be hidden in COMPLETED state");
  }

  @Test
  void refineryOrderDetail_Canceled_AsOwner_ShouldShowSaveButton_ButHideStoreAndCancel()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();

    RefineryOrderDto order =
        new RefineryOrderDto(
            orderId,
            new UserReferenceDto(ownerId, "Owner", null, null, null),
            null,
            null,
            java.time.Instant.now(),
            60L,
            100.0,
            0d,
            0d,
            0d,
            null,
            List.of(),
            RefineryOrderStatus.CANCELED,
            null,
            1L,
            null);

    when(backendApiClient.get(
            eq("/api/v1/refinery-orders/{id}"), eq(RefineryOrderDto.class), eq(orderId)))
        .thenReturn(order);
    when(backendApiClient.get(
            eq("/api/v1/settings/refinery.rounding.mode"), eq(SystemSettingDto.class)))
        .thenReturn(new SystemSettingDto("refinery.rounding.mode", "UP", 1L));

    java.util.Map<String, Object> claims = new java.util.HashMap<>();
    claims.put(
        org.springframework.security.oauth2.core.oidc.IdTokenClaimNames.SUB, ownerId.toString());
    claims.put("preferred_username", "owner");
    org.springframework.security.oauth2.core.oidc.OidcIdToken idToken =
        new org.springframework.security.oauth2.core.oidc.OidcIdToken(
            "token-value",
            java.time.Instant.now(),
            java.time.Instant.now().plusSeconds(3600),
            claims);
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
        html.contains("Speichern"), "Save button should be visible in CANCELED state for owner");
    org.junit.jupiter.api.Assertions.assertFalse(
        html.contains("onclick=\"openStoreModal()\""),
        "Store button should be hidden in CANCELED state");
    org.junit.jupiter.api.Assertions.assertFalse(
        html.contains("id=\"refineryCancelForm\""),
        "Cancel button should be hidden in CANCELED state");
  }
}
