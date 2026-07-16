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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.*;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest
@ActiveProfiles("test")
class OfficerRefineryAccessTest {

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
  void officer_ShouldBeAbleToSelectUserInCreateForm() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    UserDto userDto =
        new UserDto(
            userId,
            "officer",
            "Officer",
            "Officer",
            null,
            null,
            null,
            Set.of("OFFICER"),
            Collections.emptySet(),
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/users/me"), eq(UserDto.class))).thenReturn(userDto);
    // #1193: the owner picker is a server-side searchable combobox (remote-users). The create form
    // defaults the owner to the caller and seeds only that one option via a single-user lookup — no
    // preloaded roster. Stub the seed lookup so the box shows the officer's name.
    when(backendApiClient.get(eq("/api/v1/users/" + userId), eq(UserDto.class)))
        .thenReturn(userDto);

    // Mock other data
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.get(
            eq("/api/v1/settings/refinery.rounding.mode"), eq(SystemSettingDto.class)))
        .thenReturn(new SystemSettingDto("refinery.rounding.mode", "UP", 1L));

    Map<String, Object> claims = new HashMap<>();
    claims.put(IdTokenClaimNames.SUB, userId.toString());
    claims.put("preferred_username", "officer");
    OidcIdToken idToken =
        new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600), claims);
    OidcUser oidcUser =
        new DefaultOidcUser(
            Collections.singletonList(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_OFFICER")),
            idToken);
    OAuth2AuthenticationToken auth =
        new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");

    // When & Then
    mockMvc
        .perform(get("/refinery-orders/create").with(authentication(auth)))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("disabled=\"disabled\" id=\"ownerId\""))))
        .andExpect(content().string(containsString("id=\"ownerId\"")))
        // The picker is the enabled server-side searchable combobox (not a preloaded dropdown), and
        // it seeds the defaulted owner (the officer) so edit mode shows a name, not a raw id.
        .andExpect(content().string(containsString("data-krt-combobox=\"remote-users\"")))
        .andExpect(content().string(containsString("Officer")))
        // REQ-FE-016: the goods row's input-material select opts into the local-filter combobox,
        // and the inert options catalog for JS-added rows renders alongside it.
        .andExpect(
            content()
                .string(containsString("data-trigger=\"rfc-update-output\" data-krt-combobox")))
        .andExpect(content().string(containsString("id=\"refinery-material-options-template\"")));
  }

  @Test
  void officer_ShouldSeeEditButtonsInDetails() throws Exception {
    // Given
    UUID officerId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    UserDto officerDto =
        new UserDto(
            officerId,
            "officer",
            "Officer",
            "Officer",
            null,
            null,
            null,
            Set.of("OFFICER"),
            Collections.emptySet(),
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/users/me"), eq(UserDto.class))).thenReturn(officerDto);

    UserReferenceDto ownerRef = new UserReferenceDto(otherUserId, "other", "Other", "Other", null);
    RefineryOrderDto orderDto =
        new RefineryOrderDto(
            orderId,
            ownerRef,
            null,
            null,
            java.time.Instant.now(),
            60L,
            1000.0,
            0d,
            0d,
            0d,
            null,
            Collections.emptyList(),
            RefineryOrderStatus.OPEN,
            null,
            1L,
            null);
    when(backendApiClient.get(eq("/api/v1/refinery-orders/" + orderId), eq(RefineryOrderDto.class)))
        .thenReturn(orderDto);

    when(backendApiClient.get(
            eq("/api/v1/settings/refinery.rounding.mode"), eq(SystemSettingDto.class)))
        .thenReturn(new SystemSettingDto("refinery.rounding.mode", "UP", 1L));

    Map<String, Object> claims = new HashMap<>();
    claims.put(IdTokenClaimNames.SUB, officerId.toString());
    claims.put("preferred_username", "officer");
    OidcIdToken idToken =
        new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600), claims);
    OidcUser oidcUser =
        new DefaultOidcUser(
            Collections.singletonList(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "ROLE_OFFICER")),
            idToken);
    OAuth2AuthenticationToken auth =
        new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");

    // When & Then
    mockMvc
        .perform(get("/refinery-orders/" + orderId).with(authentication(auth)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Speichern")))
        .andExpect(content().string(containsString("Abbrechen")))
        .andExpect(content().string(containsString("Einlagern")))
        // REQ-FE-016: the editable details form's input-material select opts into the local-filter
        // combobox (the form seeds one blank goods row even for an order without goods), and the
        // canEdit-gated inert options catalog for JS-added rows renders alongside it.
        .andExpect(
            content()
                .string(containsString("data-trigger=\"rod-update-output\" data-krt-combobox")))
        .andExpect(content().string(containsString("id=\"refinery-material-options-template\"")));
  }
}
