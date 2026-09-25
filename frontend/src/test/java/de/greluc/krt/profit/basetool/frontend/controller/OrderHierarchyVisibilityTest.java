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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
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

@SpringBootTest
@ActiveProfiles("test")
class OrderHierarchyVisibilityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.capabilities(true, true, true));
  }

  @Test
  void orderDetail_AsOfficer_ShouldShowLogisticianButtons() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            1,
            null,
            null,
            "Handle",
            null,
            1,
            "OPEN",
            "MATERIAL",
            true,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.time.Instant.now(),
            1L,
            null,
            false);

    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);
    when(backendApiClient.get(
            eq("/api/v1/users/me"),
            eq(de.greluc.krt.profit.basetool.frontend.model.dto.UserDto.class)))
        .thenReturn(null);
    MaterialDto distinctMaterial =
        new MaterialDto(
            UUID.randomUUID(),
            "Quantainium-Distinct",
            null,
            "SCU",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Boolean.TRUE,
            null,
            Boolean.TRUE,
            0L);
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_JOB_ORDER), anyTypeRef()))
        .thenReturn(java.util.List.of(distinctMaterial));

    java.util.Map<String, Object> claims = new java.util.HashMap<>();
    claims.put(
        org.springframework.security.oauth2.core.oidc.IdTokenClaimNames.SUB, userId.toString());
    claims.put("preferred_username", "testuser");
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
            get("/orders/" + orderId)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(authToken)))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Bearbeiten")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "data-role=\"material-select\""
                            + " data-krt-combobox=\"remote-materials-joborder\"")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Quantainium-Distinct"))));
  }
}
