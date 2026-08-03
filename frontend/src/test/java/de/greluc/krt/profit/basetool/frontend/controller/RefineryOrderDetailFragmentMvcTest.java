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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
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
 * MVC render tests for the {@code ?fragment=} seams the refinery-order detail page gained in #1238
 * (REQ-FE-001 / REQ-FE-015): {@code order} (the edit form, goods editor and status-gated action
 * row) and {@code store} (the Einlagern dialog's rows). These are what an in-place save and the
 * {@code refinery-order:{id}} live-sync receiver re-render, so each must come back section-sized —
 * never as a whole page, which {@code krtFetch.swap} would nest inside the small swap container.
 *
 * <p>Also pins the two properties that make the seam safe to drive from a peer's socket frame: an
 * unrecognised fragment name and a backend failure both degrade to the section-sized inline error
 * instead of a redirect, and each fragment skips the catalog lookup only the other one needs
 * (ADR-0078/ADR-0081 fragment-gating).
 */
@SpringBootTest
@ActiveProfiles("test")
class RefineryOrderDetailFragmentMvcTest {

  /** Marker text of the section-sized inline error fragment (the EN bundle is not active here). */
  private static final String SECTION_ERROR_TEXT = "Der Abschnitt konnte nicht aktualisiert werden";

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

  /** Stubs the order read for {@code orderId} with a single SCU output good. */
  private void stubOrder(UUID orderId, UUID userId) {
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
  }

  private String render(UUID orderId, UUID userId, String query) throws Exception {
    return mockMvc
        .perform(
            get("/refinery-orders/" + orderId + query)
                .with(authentication(logisticianToken(userId))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void fullPage_rendersBothSwapContainers_andParksTheErrorFragmentInAnInertTemplate()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    stubOrder(orderId, userId);

    String html = render(orderId, userId, "");

    // The stable swap containers the seam map targets must exist on the full page, otherwise every
    // section refresh silently resolves to "container absent" and does nothing.
    assertThat(html).as("order swap container").contains("id=\"refinery-order-results\"");
    assertThat(html).as("store swap container").contains("id=\"refinery-store-results\"");
    // The error fragment lives inside a <template>, so it ships in the markup but the browser never
    // paints it. A bare <p> here would show as a stray red error line on every normal page load.
    assertThat(html).contains("<template id=\"refinery-fragment-error-tpl\">");
    int templateStart = html.indexOf("<template id=\"refinery-fragment-error-tpl\">");
    int templateEnd = html.indexOf("</template>", templateStart);
    assertThat(html.indexOf(SECTION_ERROR_TEXT))
        .as("the section error text appears only inside the inert <template>")
        .isBetween(templateStart, templateEnd);
  }

  @Test
  void orderFragment_rendersOnlyTheMainForm_andSkipsTheJobOrderLookup() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    stubOrder(orderId, userId);

    String html = render(orderId, userId, "?fragment=order");

    assertThat(html).as("main edit form").contains("id=\"refineryOrderMainForm\"");
    // Section-sized: no page shell, and none of the store dialog (its own fragment covers that).
    assertThat(html).as("no page shell").doesNotContain("<!DOCTYPE");
    assertThat(html).as("store dialog is a separate section").doesNotContain("id=\"storeForm\"");
    // The fragment must not re-emit its own container, which krtFetch.swap would nest inside
    // itself.
    assertThat(html).as("no re-nested container").doesNotContain("id=\"refinery-order-results\"");
    // Fragment-gating: the active-job-order lookup backs only the store dialog's Auftrag picker.
    verify(backendApiClient, never()).get(eq("/api/v1/orders/lookup"), anyTypeRef());
  }

  @Test
  void storeFragment_rendersOnlyTheStoreForm_andSkipsTheMissionsCatalog() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    stubOrder(orderId, userId);

    String html = render(orderId, userId, "?fragment=store");

    assertThat(html).as("store form").contains("id=\"storeForm\"");
    assertThat(html).as("no page shell").doesNotContain("<!DOCTYPE");
    assertThat(html).as("main form is a separate section").doesNotContain("refineryOrderMainForm");
    assertThat(html).as("no re-nested container").doesNotContain("id=\"refinery-store-results\"");
    // Fragment-gating: the missions catalog is a size=1000 uncached read used only by the `order`
    // section's Einsatz picker, so a store refresh must not pay for it.
    verify(backendApiClient, never()).get(contains("/api/v1/missions"), anyTypeRef());
  }

  @Test
  void unknownFragment_rendersTheSectionSizedError_notAWholePage() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    stubOrder(orderId, userId);

    String html = render(orderId, userId, "?fragment=bogus");

    assertThat(html).contains(SECTION_ERROR_TEXT);
    assertThat(html).as("no page shell").doesNotContain("<!DOCTYPE");
    // Rendered bare, without the <template> wrapper it is parked in on the full page.
    assertThat(html).doesNotContain("<template");
  }

  @Test
  void fragmentLoadFailure_degradesToTheSectionError_neverARedirect() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    // A backend hiccup on a peer-driven refresh must not answer with a redirect: krtFetch.swap
    // bails on res.redirected and leaves the section silently stale.
    when(backendApiClient.get(eq("/api/v1/refinery-orders/" + orderId), eq(RefineryOrderDto.class)))
        .thenThrow(
            new BackendServiceException(
                "boom", null, 500, null, null, Collections.emptyList(), null));

    String html = render(orderId, userId, "?fragment=order");

    assertThat(html).contains(SECTION_ERROR_TEXT);
  }

  @Test
  void fullPageLoadFailure_stillRedirectsToTheList() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/refinery-orders/" + orderId), eq(RefineryOrderDto.class)))
        .thenThrow(
            new BackendServiceException(
                "boom", null, 500, null, null, Collections.emptyList(), null));

    mockMvc
        .perform(get("/refinery-orders/" + orderId).with(authentication(logisticianToken(userId))))
        .andExpect(status().is3xxRedirection());
  }
}
