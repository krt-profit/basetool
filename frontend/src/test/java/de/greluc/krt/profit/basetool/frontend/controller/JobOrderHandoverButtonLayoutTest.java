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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.CapabilityFlagsAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifiziert, dass der Button "Uebergabe protokollieren" auf der Auftragsdetailseite unterhalb des
 * Bearbeiter-Bereichs erscheint und nicht mehr im Header-Bereich.
 *
 * <p>Konkret wird geprueft:
 *
 * <ul>
 *   <li>Der Button erscheint nach dem Bearbeiter-Bereich (assignees) im HTML.
 *   <li>Der Button erscheint nicht mehr im Header-Navigationsbereich (flex-between).
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderHandoverButtonLayoutTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // The logistician caller is a non-admin, so the order-detail profit gate would otherwise
    // redirect to /orders/create. Stub the capability as a profit-eligible viewer.
    when(backendApiClient.get(
            "/api/v1/me/capabilities", CapabilityFlagsAdvice.CapabilitiesResponse.class))
        .thenReturn(new CapabilityFlagsAdvice.CapabilitiesResponse(true, true, true));
  }

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

  @Test
  void orderDetail_HandoverButton_ShouldAppearAfterAssigneesSection_NotInHeader() throws Exception {
    // Given
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
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);

    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);
    when(backendApiClient.get(
            eq("/api/v1/users/me"),
            eq(de.greluc.krt.profit.basetool.frontend.model.dto.UserDto.class)))
        .thenReturn(null);

    // When
    MvcResult result =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn();

    String html = result.getResponse().getContentAsString();

    // Then: Button muss nach dem Bearbeiter-Bereich erscheinen. Anchored on the button's own
    // data-testid marker: the former "openHandoverModal()" needle matched the inline script that
    // #924 moved into static/js/orders-detail.js (and, sitting at the end of the body, it was
    // always after every markup index anyway).
    int assigneesSectionIndex = html.indexOf("Bearbeiter");
    int handoverButtonIndex = html.indexOf("data-testid=\"order-handover-open\"");

    assertThat(assigneesSectionIndex)
        .as("Bearbeiter-Bereich muss im HTML vorhanden sein")
        .isGreaterThan(0);
    assertThat(handoverButtonIndex)
        .as("Handover-Button muss im HTML vorhanden sein")
        .isGreaterThan(0);
    assertThat(handoverButtonIndex)
        .as("Handover-Button muss nach dem Bearbeiter-Bereich erscheinen")
        .isGreaterThan(assigneesSectionIndex);

    // Then: Button darf nicht im Header-Bereich (flex-between) erscheinen
    int headerEnd = html.indexOf("</div>", html.indexOf("flex-between"));
    assertThat(handoverButtonIndex)
        .as("Handover-Button darf nicht im Header-Bereich (flex-between) erscheinen")
        .isGreaterThan(headerEnd);
  }
}
