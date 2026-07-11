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
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStatus;
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
 * MVC render test guarding the SCU input-hint gating in the refinery-order store dialog
 * (refinery-orders-details.html). The store dialog builds one row per refinery output good; the
 * decimal-scale hint ({@code fragments/scu-hint :: scuHint}) must render ONLY on SCU-measured rows,
 * never on PIECE rows.
 *
 * <p>Regression guard for the Thymeleaf attribute-precedence trap: {@code th:replace} (precedence
 * 1) is processed BEFORE {@code th:if} (precedence 3), so putting both on the same element made the
 * SCU-only guard dead and rendered the hint on every row — including PIECE rows. The fix gates an
 * outer {@code th:block} while {@code th:replace} sits alone on the inner one; this test fails on
 * the pre-fix markup (hint count 2) and passes after it (count 1).
 */
@SpringBootTest
@ActiveProfiles("test")
class RefineryStoreScuHintRenderTest {

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
  void storeDialog_ScuHint_RendersOnlyForScuRows_NotPieceRows() throws Exception {
    // Given: a refinery order with two output goods — one SCU-measured, one PIECE-measured. The
    // detail controller builds one store-dialog row per good, each carrying a quantityType derived
    // from its output material (RefineryOrderPageController#viewOrderDetail, "PIECE" -> PIECE else
    // SCU). The PIECE branch is live code, so a PIECE store row is a real, reachable case.
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    RefineryGoodDto scuGood =
        new RefineryGoodDto(
            UUID.randomUUID(),
            material("Quantanium Ore", "SCU"),
            100,
            material("Refined Quantanium", "SCU"),
            100,
            100,
            null);
    RefineryGoodDto pieceGood =
        new RefineryGoodDto(
            UUID.randomUUID(),
            material("Recovered Bundle", "PIECE"),
            5,
            material("Salvaged Component", "PIECE"),
            5,
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
            List.of(scuGood, pieceGood),
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

    // Then: both store rows render (proves the loop produced a PIECE row alongside the SCU row)...
    assertThat(html).as("SCU output store row present").contains("Refined Quantanium");
    assertThat(html).as("PIECE output store row present").contains("Salvaged Component");

    // ...but the SCU decimal-scale hint renders exactly once — on the SCU row only. With the
    // same-element th:if/th:replace precedence bug the hint rendered unconditionally (count == 2).
    int scuHintCount = html.split("class=\"scu-hint\"", -1).length - 1;
    assertThat(scuHintCount)
        .as("scu-hint disc renders once (SCU row only), not on the PIECE row")
        .isEqualTo(1);
  }
}
