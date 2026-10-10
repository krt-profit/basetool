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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedAppMassChangeRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedAppMassChangeResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.IngestHandoffService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * MVC test for {@link ConnectedAppsConfirmController} and {@link
 * ConnectedAppsConfirmRelayController} (REQ-XCH-021, ADR-0110): the page load never consumes the
 * handoff, the script's load consumes it once and keeps the change set in the session until its
 * staging lifetime runs out, and the confirmation applies exactly that change set once.
 */
@SpringBootTest
class ConnectedAppsConfirmControllerMvcTest {

  private static final String HANDOFF = "h4ndoff_Id-0123456789";
  private static final String SUB = "44444444-4444-4444-4444-4444444440c9";

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private IngestHandoffService ingestHandoffService;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void thePageLoadHandsTheIdOnWithoutConsumingIt() throws Exception {
    mockMvc
        .perform(
            get("/connected-apps/confirm")
                .param("handoff", HANDOFF)
                .with(user("member").roles("KRT_MEMBER")))
        .andExpect(status().isOk())
        .andExpect(view().name("connected-apps-confirm"))
        .andExpect(model().attribute("pendingHandoffId", HANDOFF))
        .andExpect(content().string(containsString("data-handoff-id=\"" + HANDOFF + "\"")));

    verify(ingestHandoffService, never()).consume(anyString(), anyString(), any(), any());
  }

  /**
   * The page follows the form pattern (REQ-UI-027): its eyebrow leads back to the connected
   * applications, the confirmation sits on one card, and discarding comes before confirming.
   *
   * @throws Exception if the request fails
   */
  @Test
  void thePageLeadsBackThroughItsEyebrow() throws Exception {
    String html =
        mockMvc
            .perform(
                get("/connected-apps/confirm")
                    .param("handoff", HANDOFF)
                    .with(user("member").roles("KRT_MEMBER")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .containsPattern("class=\"page-eyebrow\" href=\"/connected-apps\"")
        .contains("class=\"form-layout card cac-card\"")
        .doesNotContain("hud-box")
        .doesNotContain("class=\"greeting");
    assertThat(html.indexOf("id=\"cac-discard\"")).isLessThan(html.indexOf("id=\"cac-confirm\""));
  }

  @Test
  void aMalformedIdIsNotHandedOn() throws Exception {
    mockMvc
        .perform(
            get("/connected-apps/confirm")
                .param("handoff", "../x")
                .with(user("member").roles("KRT_MEMBER")))
        .andExpect(status().isOk())
        .andExpect(model().attribute("pendingHandoffId", org.hamcrest.Matchers.nullValue()))
        .andExpect(content().string(containsString("id=\"cac-box\"")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("../x"))));
  }

  @Test
  void theLoadConsumesOncePreviewsAndTheConfirmationAppliesThatChangeSetOnce() throws Exception {
    Instant stagedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    stubStaged(stagedAt);
    ConnectedAppMassChangeRequestDto expected = request(stagedAt);
    when(backendApiClient.post(
            eq("/api/v1/connected-apps/mass-changes/preview"),
            eq(expected),
            eq(ConnectedAppMassChangeResultDto.class)))
        .thenReturn(new ConnectedAppMassChangeResultDto("VerseKit", "blueprints", true, 1, 0, 0));
    when(backendApiClient.post(
            eq("/api/v1/connected-apps/mass-changes/confirm"),
            eq(expected),
            eq(ConnectedAppMassChangeResultDto.class)))
        .thenReturn(new ConnectedAppMassChangeResultDto("VerseKit", "blueprints", false, 1, 0, 0));
    MockHttpSession session = new MockHttpSession();

    step("load", session)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientName").value("VerseKit"))
        .andExpect(jsonPath("$.dryRun").value(true));
    step("apply", session).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));
    step("apply", session).andExpect(status().isNotFound());

    verify(backendApiClient)
        .post(
            eq("/api/v1/connected-apps/mass-changes/confirm"),
            eq(expected),
            eq(ConnectedAppMassChangeResultDto.class));
  }

  @Test
  void aBrowserSuppliedChangeSetOrStagingTimeIsNeverBelieved() throws Exception {
    Instant stagedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    stubStaged(stagedAt);
    MockHttpSession session = new MockHttpSession();
    when(backendApiClient.post(anyString(), any(), eq(ConnectedAppMassChangeResultDto.class)))
        .thenReturn(new ConnectedAppMassChangeResultDto("VerseKit", "blueprints", true, 1, 0, 0));
    String hostile =
        "{\"handoffId\":\""
            + HANDOFF
            + "\",\"clientId\":\"other\",\"installationKey\":\"x\",\"resource\":\"stock\","
            + "\"changeSet\":\"{\\\"ops\\\":[]}\",\"stagedAt\":\""
            + Instant.now().plusSeconds(3600)
            + "\"}";

    stepWith("load", session, hostile).andExpect(status().isOk());
    stepWith("apply", session, hostile).andExpect(status().isOk());

    verify(backendApiClient)
        .post(
            eq("/api/v1/connected-apps/mass-changes/confirm"),
            eq(request(stagedAt)),
            eq(ConnectedAppMassChangeResultDto.class));
  }

  @Test
  void aBrowserSuppliedFreshStagingTimeDoesNotRescueAnExpiredKeptChangeSet() throws Exception {
    MockHttpSession session = new MockHttpSession();
    Instant stale =
        Instant.now().minus(ConnectedAppsConfirmRelayController.STAGING_LIFETIME).minusSeconds(1);
    session.setAttribute(
        ConnectedAppsConfirmRelayController.SESSION_KEY,
        new HashMap<>(
            Map.of(HANDOFF, JsonMapper.builder().build().writeValueAsString(request(stale)))));

    stepWith(
            "apply",
            session,
            "{\"handoffId\":\"" + HANDOFF + "\",\"stagedAt\":\"" + Instant.now() + "\"}")
        .andExpect(status().isNotFound());

    verify(backendApiClient, never()).post(anyString(), any(), any());
  }

  @Test
  void nothingIsAppliedWithoutALoadOrAfterADiscard() throws Exception {
    MockHttpSession session = new MockHttpSession();
    step("apply", session).andExpect(status().isNotFound());

    stubStaged(Instant.now());
    step("load", session);
    step("discard", session).andExpect(status().isNoContent());
    step("apply", session).andExpect(status().isNotFound());

    verify(backendApiClient, never())
        .post(eq("/api/v1/connected-apps/mass-changes/confirm"), any(), any());
  }

  @Test
  void anExpiredHandoffIsNotFound() throws Exception {
    when(ingestHandoffService.consume(eq(SUB), eq(HANDOFF), eq(HandoffKind.MASS_CHANGE), any()))
        .thenReturn(Optional.empty());

    step("load", new MockHttpSession()).andExpect(status().isNotFound());
  }

  @Test
  void aHandoffStagedLongerAgoThanTheLifetimeOrWithoutAStagingTimeIsNotLoaded() throws Exception {
    stubStaged(Instant.now().minus(ConnectedAppsConfirmRelayController.STAGING_LIFETIME));
    step("load", new MockHttpSession()).andExpect(status().isNotFound());

    stubStaged(null);
    step("load", new MockHttpSession()).andExpect(status().isNotFound());

    verify(backendApiClient, never()).post(anyString(), any(), any());
  }

  @Test
  void aKeptChangeSetExpiresWithItsStagingLifetime() throws Exception {
    MockHttpSession session = new MockHttpSession();
    Instant stale =
        Instant.now().minus(ConnectedAppsConfirmRelayController.STAGING_LIFETIME).minusSeconds(1);
    session.setAttribute(
        ConnectedAppsConfirmRelayController.SESSION_KEY,
        new HashMap<>(
            Map.of(HANDOFF, JsonMapper.builder().build().writeValueAsString(request(stale)))));

    step("apply", session).andExpect(status().isNotFound());

    verify(backendApiClient, never()).post(anyString(), any(), any());
    assertThat((Map<?, ?>) session.getAttribute(ConnectedAppsConfirmRelayController.SESSION_KEY))
        .isEmpty();
  }

  @Test
  void loadingAnotherBatchDropsTheExpiredOnesKept() throws Exception {
    MockHttpSession session = new MockHttpSession();
    Instant stale =
        Instant.now().minus(ConnectedAppsConfirmRelayController.STAGING_LIFETIME).minusSeconds(1);
    session.setAttribute(
        ConnectedAppsConfirmRelayController.SESSION_KEY,
        new HashMap<>(
            Map.of("older", JsonMapper.builder().build().writeValueAsString(request(stale)))));
    stubStaged(Instant.now());

    step("load", session);

    Map<?, ?> kept =
        (Map<?, ?>) session.getAttribute(ConnectedAppsConfirmRelayController.SESSION_KEY);
    assertThat(kept.keySet().stream().map(String::valueOf).toList()).containsExactly(HANDOFF);
  }

  /**
   * Builds the change set the page hands to the backend for the stubbed handoff.
   *
   * @param stagedAt when the gateway staged it
   * @return the request
   */
  private static @NotNull ConnectedAppMassChangeRequestDto request(@NotNull Instant stagedAt) {
    return new ConnectedAppMassChangeRequestDto(
        "versekit",
        "key-1",
        "blueprints",
        "{\"ops\":[{\"op\":\"remove\",\"key\":\"k\"}]}",
        stagedAt);
  }

  /**
   * Stubs the gateway's staged change set for the test member.
   *
   * @param stagedAt when the gateway staged it, or {@code null} for a document without the time
   */
  private void stubStaged(@Nullable Instant stagedAt) {
    when(ingestHandoffService.consume(
            eq(SUB),
            eq(HANDOFF),
            eq(HandoffKind.MASS_CHANGE),
            eq(ConnectedAppsConfirmRelayController.StagedMassChange.class)))
        .thenReturn(
            Optional.of(
                new ConnectedAppsConfirmRelayController.StagedMassChange(
                    "versekit",
                    "key-1",
                    "blueprints",
                    JsonMapper.builder()
                        .build()
                        .readTree("{\"ops\":[{\"op\":\"remove\",\"key\":\"k\"}]}"),
                    stagedAt)));
  }

  private ResultActions step(@NotNull String step, @NotNull MockHttpSession session)
      throws Exception {
    return stepWith(step, session, "{\"handoffId\":\"" + HANDOFF + "\"}");
  }

  private ResultActions stepWith(
      @NotNull String step, @NotNull MockHttpSession session, @NotNull String body)
      throws Exception {
    return mockMvc.perform(
        post("/connected-apps/confirm/" + step)
            .session(session)
            .header("X-Requested-With", "XMLHttpRequest")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .with(member())
            .with(csrf()));
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor
      member() {
    return oidcLogin()
        .idToken(token -> token.subject(SUB))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }
}
