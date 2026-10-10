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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.model.MissionReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the refinery-order detail on the form pattern (REQ-UI-027): a back-link head with the
 * run's status badge, five numbered sections with the computed end, the status card with its steps,
 * profit and the store action as the one primary action of a ready run, the assignment card, and
 * the save bar as an outline action.
 */
@SpringBootTest
class RefineryOrderDetailPatternRenderTest {

  /** The owner of the order, who views it. */
  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");

  /** The order under test. */
  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000001042");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds the MockMvc and stubs the layout read. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.capabilities(true, true, true));
  }

  /**
   * A member token for the owner.
   *
   * @return the authentication
   */
  private static @NotNull OAuth2AuthenticationToken ownerToken() {
    OidcIdToken idToken =
        new OidcIdToken(
            "token-value",
            Instant.now(),
            Instant.now().plusSeconds(3600),
            Map.of(IdTokenClaimNames.SUB, OWNER.toString(), "preferred_username", "owner"));
    OidcUser user =
        new DefaultOidcUser(
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")), idToken);
    return new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak");
  }

  /**
   * A material of the catalogue.
   *
   * @param name the material name
   * @return the material
   */
  private static @NotNull MaterialDto material(@NotNull String name) {
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

  /**
   * Stubs the order read and renders the detail page in German.
   *
   * @param status the order status
   * @param startedAt the start of the run, or {@code null}
   * @param durationMinutes the duration, or {@code null}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(
      @NotNull RefineryOrderStatus status,
      @Nullable Instant startedAt,
      @Nullable Long durationMinutes)
      throws Exception {
    RefineryGoodDto good =
        new RefineryGoodDto(
            UUID.randomUUID(),
            material("Quantanium-Erz"),
            2300,
            material("Quantanium"),
            1840,
            812,
            null);
    RefineryOrderDto order =
        new RefineryOrderDto(
            ORDER_ID,
            new UserReferenceDto(OWNER, "greluc", null, "greluc", 0),
            new LocationDto(
                UUID.randomUUID(), "ARC-L1 Wide Forest Station", null, false, false, 1L),
            new MissionReferenceDto(UUID.randomUUID(), "Quantanium-Abbau", "ACTIVE", null),
            startedAt,
            durationMinutes,
            118200d,
            7500d,
            612000d,
            486300d,
            null,
            List.of(good),
            status,
            null,
            3L,
            null);
    when(backendApiClient.get(
            eq("/api/v1/refinery-orders/{id}"), eq(RefineryOrderDto.class), eq(ORDER_ID)))
        .thenReturn(order);
    return mockMvc
        .perform(
            get("/refinery-orders/" + ORDER_ID)
                .locale(Locale.GERMAN)
                .with(authentication(ownerToken())))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** A ready run: badge, sections, steps, profit, store as the primary action, outline save. */
  @Test
  void readyRunRendersTheFormPattern() throws Exception {
    String html = render(RefineryOrderStatus.OPEN, Instant.now().minus(3, ChronoUnit.HOURS), 60L);
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));

    assertThat(main)
        .contains("data-testid=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\" href=\"/refinery-orders\"")
        .contains("<h1>Auftrag #1042</h1>")
        .containsPattern("class=\"status-badge status-active\"[^>]*>Abholbereit<")
        .doesNotContain("hud-box")
        .doesNotContain("class=\"greeting");
    assertThat(main.split("class=\"form-section\"", -1)).hasSize(6);
    assertThat(main)
        .contains("class=\"form-layout card rod-form\"")
        .contains("1 · Raffinerie")
        .contains("2 · Zeit")
        .contains("3 · Materialien")
        .contains("4 · Finanzen")
        .contains("id=\"endsAtDisplay\" class=\"field-computed\"")
        .contains("· berechnet")
        .contains("class=\"rod-good rod-good--head\"")
        .contains("id=\"inputMaterialId_0\"")
        .contains("id=\"outputQuantity_0\"");
    assertThat(main)
        .contains("class=\"card card--accent rod-status\"")
        .contains("class=\"ablauf rod-steps\"")
        .containsPattern("class=\"step step--done\"")
        .containsPattern("class=\"step step--now\"")
        .contains("Verkauf 612.000 – Kosten 125.700")
        .contains("Zugeordnet")
        .contains("Quantanium-Abbau")
        .contains("Besitzer greluc");
    assertThat(main)
        .containsPattern("class=\"btn btn--cta rod-store\"\\s+data-trigger=\"rod-open-store\"")
        .doesNotContainPattern("rod-store\"[^>]*hidden=\"hidden\"")
        .contains("class=\"form-actions--sticky\"")
        .contains("class=\"btn btn-outline\" data-testid=\"refinery-save\"")
        .contains("id=\"refineryCancelForm\"")
        .contains("overflow-menu__item--danger");
    String outsideDialog = main.substring(0, main.indexOf("id=\"storeModal\""));
    assertThat(outsideDialog.split("btn--cta", -1)).hasSize(2);
    assertThat(outsideDialog).doesNotContainPattern("class=\"[^\"]*krtm-");
  }

  /** A running run keeps the store action hidden until its end and shows the running badge. */
  @Test
  void runningRunHidesTheStoreAction() throws Exception {
    String html =
        render(RefineryOrderStatus.IN_PROGRESS, Instant.now().minus(10, ChronoUnit.MINUTES), 120L);

    assertThat(html)
        .containsPattern("class=\"status-badge status-planned\"[^>]*>L\u00e4uft<")
        .containsPattern("class=\"btn btn--cta rod-store\"[^>]*hidden=\"hidden\"")
        .contains("data-ready-at=");
  }

  /** A stored run shows the completed steps and offers neither storing nor canceling. */
  @Test
  void storedRunOffersNoStoreOrCancel() throws Exception {
    String html = render(RefineryOrderStatus.COMPLETED, null, null);

    assertThat(html)
        .containsPattern("class=\"status-badge status-completed\"[^>]*>Eingelagert<")
        .doesNotContain("data-trigger=\"rod-open-store\"")
        .doesNotContain("id=\"refineryCancelForm\"");
  }
}
