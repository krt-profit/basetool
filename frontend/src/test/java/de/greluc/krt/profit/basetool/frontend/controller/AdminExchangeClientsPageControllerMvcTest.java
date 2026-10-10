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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoPreviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRunDetailDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRunDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientStatusRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientUsageDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test for {@link AdminExchangeClientsPageController} (REQ-XCH-003, REQ-XCH-034): the page and
 * its {@code registry} and {@code undoRuns} swaps render the switch, the clients and the bulk undo
 * runs, and every write is relayed to the backend's admin registry and bulk undo.
 */
@SpringBootTest
class AdminExchangeClientsPageControllerMvcTest {

  private static final UUID ID = UUID.fromString("7a0c7a0c-0000-4000-8000-00000000c11e");
  private static final UUID RUN = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000000d0");

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Stubs one active client with an override and a contact, and the switch turned on. */
  private void stubRegistry() {
    ExchangeClientDto client =
        new ExchangeClientDto(
            ID,
            "versekit",
            "VerseKit",
            "ACTIVE",
            List.of("exchange.connect", "exchange.stock.read"),
            "2.1.0",
            "https://versekit.example/privacy",
            240,
            null,
            Instant.parse("2026-09-27T08:00:00Z"),
            null,
            3L);
    when(backendApiClient.get(eq("/api/v1/connected-apps/admin/clients"), anyTypeRef()))
        .thenReturn(List.of(client));
    when(backendApiClient.get("/api/v1/connected-apps/admin/settings", ExchangeSettingsDto.class))
        .thenReturn(new ExchangeSettingsDto(true, null, 5L));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePageShowsTheSwitchTheClientAndEveryCapabilityToGrant() throws Exception {
    stubRegistry();

    mockMvc
        .perform(get("/admin/exchange-clients"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/exchange-clients"))
        .andExpect(
            model().attribute("capabilities", AdminExchangeClientsPageController.CAPABILITIES))
        .andExpect(model().attributeDoesNotExist("error"))
        .andExpect(content().string(containsString("id=\"xc-registry-host\"")))
        .andExpect(content().string(containsString("data-enabled=\"true\"")))
        .andExpect(content().string(containsString("data-version=\"5\"")))
        .andExpect(content().string(containsString(">VerseKit<")))
        .andExpect(content().string(containsString("data-version=\"3\"")))
        .andExpect(content().string(containsString("href=\"https://versekit.example/privacy\"")))
        .andExpect(content().string(containsString("value=\"exchange.drafts.refinery\"")))
        .andExpect(
            content().string(containsString("id=\"xc-requestsPerMinute\" min=\"1\" max=\"1200\"")))
        .andExpect(
            content().string(containsString("id=\"xc-writesPerDay\" min=\"1\" max=\"5000\"")))
        .andExpect(content().string(not(containsString("??exchange.capability"))))
        .andExpect(content().string(not(containsString("??admin.exchangeClients"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void eachClientShowsItsConnectedMembersLastActivityAndTheGrafanaLink() throws Exception {
    stubRegistry();
    when(backendApiClient.get(eq("/api/v1/connected-apps/admin/clients/usage"), anyTypeRef()))
        .thenReturn(
            List.of(new ExchangeClientUsageDto(ID, 7L, Instant.parse("2026-09-27T09:30:00Z"))));

    mockMvc
        .perform(get("/admin/exchange-clients"))
        .andExpect(status().isOk())
        .andExpect(content().string(matchesPattern("(?s).*class=\"xc-members\"[^>]*>7<.*")))
        .andExpect(content().string(containsString("27.09.2026 09:30 UTC")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "href=\"https://grafana.profit-base.online/d/basetool-operations\"")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void anUnreadableUsageLeavesThePageStanding() throws Exception {
    stubRegistry();
    when(backendApiClient.get(eq("/api/v1/connected-apps/admin/clients/usage"), anyTypeRef()))
        .thenThrow(new BackendServiceException("usage down", null, 503));

    mockMvc
        .perform(get("/admin/exchange-clients"))
        .andExpect(status().isOk())
        .andExpect(model().attributeDoesNotExist("error"))
        .andExpect(content().string(matchesPattern("(?s).*class=\"xc-members\"[^>]*>0<.*")))
        .andExpect(content().string(containsString(">VerseKit<")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theRegistryFragmentRendersOnlyTheSwitchAndTheTable() throws Exception {
    stubRegistry();

    mockMvc
        .perform(get("/admin/exchange-clients").param("fragment", "registry"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/exchange-clients :: registry"))
        .andExpect(content().string(containsString("id=\"xc-table\"")))
        .andExpect(content().string(containsString("id=\"xc-switch\"")))
        .andExpect(content().string(not(containsString("id=\"xc-registry-host\""))))
        .andExpect(content().string(not(containsString("id=\"xc-form\""))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aBackendOutageRendersTheFailureInsideTheFragmentWithoutASwitch() throws Exception {
    when(backendApiClient.get(eq("/api/v1/connected-apps/admin/clients"), anyTypeRef()))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/admin/exchange-clients").param("fragment", "registry"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("error", "admin.exchangeClients.error.load"))
        .andExpect(content().string(containsString("class=\"alert alert-danger\"")))
        .andExpect(content().string(not(containsString("id=\"xc-switch\""))))
        .andExpect(content().string(not(containsString("id=\"xc-empty\""))));
  }

  /**
   * The page renders on the list pattern (REQ-UI-027): page head with the system eyebrow, the count
   * and the one primary action, no hud-box, the stacked tables in flush cards, the translated
   * status, and the run list's empty state instead of a colspan row.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void thePageRendersTheListPattern() throws Exception {
    stubRegistry();

    String html =
        mockMvc
            .perform(get("/admin/exchange-clients").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String head =
        html.substring(
            html.indexOf("data-testid=\"page-head\""), html.indexOf("id=\"xc-registry-host\""));
    assertThat(head)
        .containsPattern("class=\"page-eyebrow\"[^>]*>System &amp; Daten<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"xc-registry-host\"")
        .contains("data-xc-new");
    assertThat(head.split("btn--cta", -1)).hasSize(2);
    assertThat(html)
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("colspan")
        .contains("id=\"xc-table\" class=\"data-table data-table--stack\"")
        .contains("data-list-total=\"1\"")
        .containsPattern(
            "class=\"cell-status\">\\s*<span class=\"chip chip--success\">Freigegeben<")
        .doesNotContain(">ACTIVE<")
        .contains("id=\"xc-undo-runs-empty\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aRegistrationIsRelayedToTheBackend() throws Exception {
    when(backendApiClient.post(
            eq("/api/v1/connected-apps/admin/clients"), any(), eq(ExchangeClientDto.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/admin/exchange-clients")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"clientId\":\"versekit\",\"displayName\":\"VerseKit\","
                        + "\"capabilities\":[\"exchange.connect\"],\"requestsPerMinute\":null}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient)
        .post(
            eq("/api/v1/connected-apps/admin/clients"),
            body.capture(),
            eq(ExchangeClientDto.class));
    assertThat(body.getValue())
        .isEqualTo(
            new ExchangeClientCreateRequest(
                "versekit", "VerseKit", List.of("exchange.connect"), null, null, null, null));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aSuspensionCarriesTheVersionToTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/admin/exchange-clients/" + ID + "/suspend")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3}"))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .post(
            "/api/v1/connected-apps/admin/clients/{id}/suspend",
            new ExchangeClientStatusRequest(3L),
            ExchangeClientDto.class,
            ID);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theSwitchIsRelayedAndAConflictComesBackAsAConflict() throws Exception {
    when(backendApiClient.put(
            eq("/api/v1/connected-apps/admin/settings"),
            eq(new ExchangeSettingsUpdateRequest(false, 5L)),
            eq(ExchangeSettingsDto.class)))
        .thenThrow(new BackendServiceException("stale", null, 409));

    mockMvc
        .perform(
            put("/admin/exchange-clients/settings")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"version\":5}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void oneClientIsReturnedForTheEditForm() throws Exception {
    stubRegistry();
    when(backendApiClient.get(
            "/api/v1/connected-apps/admin/clients/{id}", ExchangeClientDto.class, ID))
        .thenReturn(
            new ExchangeClientDto(
                ID,
                "versekit",
                "VerseKit",
                "SUSPENDED",
                List.of("exchange.connect"),
                null,
                null,
                null,
                null,
                null,
                null,
                4L));

    mockMvc
        .perform(get("/admin/exchange-clients/" + ID).header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"))
        .andExpect(jsonPath("$.version").value(4));
  }

  /**
   * Builds a bulk undo run of the stubbed client.
   *
   * @param status the run's state
   * @return the run
   */
  private static ExchangeBulkUndoRunDto run(String status) {
    return new ExchangeBulkUndoRunDto(
        RUN,
        ID,
        "versekit",
        "VerseKit",
        status,
        Instant.parse("2026-09-27T12:00:00Z"),
        null,
        "SHIP",
        "Admin One",
        3,
        2,
        0,
        5,
        1,
        Instant.parse("2026-09-27T18:00:00Z"),
        null);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theUndoRunsFragmentShowsARunningRunAndAsksToBePolled() throws Exception {
    stubRegistry();
    when(backendApiClient.get(eq("/api/v1/connected-apps/admin/undo-runs"), anyTypeRef()))
        .thenReturn(List.of(run("RUNNING")));

    mockMvc
        .perform(get("/admin/exchange-clients").param("fragment", "undoRuns"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/exchange-clients :: undoRuns"))
        .andExpect(content().string(containsString("data-xc-running=\"true\"")))
        .andExpect(content().string(containsString("data-run-id=\"" + RUN + "\"")))
        .andExpect(content().string(containsString("27.09.2026 18:00 UTC")))
        .andExpect(content().string(containsString("chip chip--info")))
        .andExpect(content().string(not(containsString("id=\"xc-table\""))))
        .andExpect(content().string(not(containsString("??admin.exchangeClients"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePageOffersTheUndoPerClientAndStopsPollingWhenNothingRuns() throws Exception {
    stubRegistry();
    when(backendApiClient.get(eq("/api/v1/connected-apps/admin/undo-runs"), anyTypeRef()))
        .thenReturn(List.of(run("COMPLETED")));

    mockMvc
        .perform(get("/admin/exchange-clients"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-xc-undo")))
        .andExpect(content().string(containsString("id=\"xc-undo-modal\"")))
        .andExpect(content().string(containsString("id=\"xc-undo-run-modal\"")))
        .andExpect(content().string(containsString("data-xc-running=\"false\"")))
        .andExpect(content().string(containsString("chip chip--success")))
        .andExpect(content().string(not(containsString("??admin.exchangeClients"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aBulkUndoIsPreviewedAndStartedWithItsScope() throws Exception {
    ExchangeBulkUndoRequest scope =
        new ExchangeBulkUndoRequest(Instant.parse("2026-09-27T12:00:00Z"), null, "SHIP");
    when(backendApiClient.post(
            "/api/v1/connected-apps/admin/clients/{id}/undo/preview",
            scope,
            ExchangeBulkUndoPreviewDto.class,
            ID))
        .thenReturn(new ExchangeBulkUndoPreviewDto(scope.since(), 3, 12L, true));
    when(backendApiClient.post(
            "/api/v1/connected-apps/admin/clients/{id}/undo",
            scope,
            ExchangeBulkUndoRunDto.class,
            ID))
        .thenReturn(run("RUNNING"));
    String body = "{\"since\":\"2026-09-27T12:00:00Z\",\"resource\":\"SHIP\"}";

    mockMvc
        .perform(
            post("/admin/exchange-clients/" + ID + "/undo/preview")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members").value(3))
        .andExpect(jsonPath("$.clientActive").value(true));
    mockMvc
        .perform(
            post("/admin/exchange-clients/" + ID + "/undo")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aSecondRunIsRelayedAsAConflict() throws Exception {
    when(backendApiClient.post(
            eq("/api/v1/connected-apps/admin/clients/{id}/undo"),
            any(),
            eq(ExchangeBulkUndoRunDto.class),
            eq(ID)))
        .thenThrow(new BackendServiceException("running", null, 409));

    mockMvc
        .perform(
            post("/admin/exchange-clients/" + ID + "/undo")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"since\":\"2026-09-27T12:00:00Z\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theInstallationsAndARunsSkippedEntriesAreRelayed() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/connected-apps/admin/clients/{id}/undo/installations?since={since}"),
            anyTypeRef(),
            eq(ID),
            eq("2026-09-27T12:00:00Z")))
        .thenReturn(List.of());
    when(backendApiClient.get(
            "/api/v1/connected-apps/admin/undo-runs/{runId}",
            ExchangeBulkUndoRunDetailDto.class,
            RUN))
        .thenReturn(
            new ExchangeBulkUndoRunDetailDto(
                run("COMPLETED"),
                List.of(
                    new ExchangeBulkUndoRunDetailDto.SkippedEntry(
                        "Member", "SHIP", "Cutlass", "CHANGED_AFTERWARDS")),
                1L));

    mockMvc
        .perform(
            get("/admin/exchange-clients/" + ID + "/undo/installations")
                .param("since", "2026-09-27T12:00:00Z")
                .header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc
        .perform(
            get("/admin/exchange-clients/undo-runs/" + RUN)
                .header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skipped[0].reason").value("CHANGED_AFTERWARDS"))
        .andExpect(jsonPath("$.skippedTotal").value(1));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMemberIsRefused() throws Exception {
    mockMvc.perform(get("/admin/exchange-clients")).andExpect(status().isForbidden());
  }
}
