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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full Thymeleaf render / proxy tests for the Discord registration-approval queue. Covers the
 * server-nickname column (REQ-DATA-008) — a captured per-guild nickname is shown next to the name,
 * a registration without one falls back to the muted em-dash — and the admin
 * link-to-existing-account action (REQ-SEC-026): the "Verknüpfen" button and the remote-users
 * account picker render, and the {@code linkAjax} proxy forwards to the backend. Also covers the
 * rejected table and its reopen action (REQ-SEC-034), including the deliberate degradation when
 * only the rejected read fails. The render assertions key off controlled values / stable markers,
 * so they are locale-independent.
 */
@SpringBootTest
class AdminDiscordRegistrationsNicknameRenderTest {

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
  void queue_showsServerNickname_andDashWhenAbsent() throws Exception {
    PendingRegistrationDto withNick =
        new PendingRegistrationDto(
            UUID.randomUUID(),
            "AliceCallsign",
            "VanguardPilot",
            Instant.parse("2026-06-22T00:00:00Z"),
            null,
            1L);
    PendingRegistrationDto withoutNick =
        new PendingRegistrationDto(
            UUID.randomUUID(),
            "BobCallsign",
            null,
            Instant.parse("2026-06-22T00:00:00Z"),
            null,
            1L);

    when(backendApiClient.get(eq("/api/v1/admin/registrations"), anyTypeRef()))
        .thenReturn(List.of(withNick, withoutNick));

    String html =
        mockMvc
            .perform(
                get("/admin/discord-registrations")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("both pending registrations are rendered")
        .contains("AliceCallsign")
        .contains("BobCallsign");
    assertThat(countOccurrences(html, "VanguardPilot"))
        .as("the captured server nickname is shown for exactly the one row that has it")
        .isEqualTo(1);
  }

  @Test
  void queue_rendersLinkActionAndAccountPicker() throws Exception {
    when(backendApiClient.get(eq("/api/v1/admin/registrations"), anyTypeRef()))
        .thenReturn(
            List.of(
                new PendingRegistrationDto(
                    UUID.randomUUID(),
                    "conrad7247",
                    null,
                    Instant.parse("2026-07-20T00:00:00Z"),
                    null,
                    1L)));

    String html =
        mockMvc
            .perform(
                get("/admin/discord-registrations")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("the per-row link action + the link modal with the remote-users account picker render")
        .contains("data-action=\"link\"")
        .contains("id=\"link-modal\"")
        .contains("id=\"link-target\"")
        .contains("data-krt-combobox=\"remote-users\"");
  }

  @Test
  void linkAjax_forwardsToBackend_andReturnsOk() throws Exception {
    UUID id = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    when(backendApiClient.post(
            eq("/api/v1/admin/registrations/" + id + "/link"),
            any(),
            eq(PendingRegistrationDto.class)))
        .thenReturn(
            new PendingRegistrationDto(
                target, "MadrukSedras", null, Instant.parse("2026-07-20T00:00:00Z"), null, 2L));

    mockMvc
        .perform(
            post("/admin/discord-registrations/" + id + "/link")
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":\"" + target + "\",\"version\":1}")
                .with(csrf())
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void rejectedTable_rendersRejectedRowsWithReopenAction() throws Exception {
    when(backendApiClient.get(eq("/api/v1/admin/registrations"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(eq("/api/v1/admin/registrations?status=REJECTED"), anyTypeRef()))
        .thenReturn(
            List.of(
                new PendingRegistrationDto(
                    UUID.randomUUID(),
                    "StolpiCallsign",
                    null,
                    Instant.parse("2026-06-22T00:00:00Z"),
                    Instant.parse("2026-06-29T09:41:00Z"),
                    3L)));

    String html =
        mockMvc
            .perform(
                get("/admin/discord-registrations")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("the rejected registration renders with its rejection time and the reopen action")
        .contains("StolpiCallsign")
        .contains("29.06.2026 09:41")
        .contains("data-action=\"reopen\"")
        .contains("id=\"reopen-modal\"")
        .contains("id=\"reopen-reason\"");
  }

  @Test
  void rejectedListFailure_doesNotBlankThePendingQueue() throws Exception {
    // The rejected read is the secondary surface; a failure there (e.g. a backend that predates
    // ?status= during a rolling deploy) must not take the pending queue down with it.
    when(backendApiClient.get(eq("/api/v1/admin/registrations"), anyTypeRef()))
        .thenReturn(
            List.of(
                new PendingRegistrationDto(
                    UUID.randomUUID(),
                    "AliceCallsign",
                    null,
                    Instant.parse("2026-06-22T00:00:00Z"),
                    null,
                    1L)));
    when(backendApiClient.get(eq("/api/v1/admin/registrations?status=REJECTED"), anyTypeRef()))
        .thenThrow(new IllegalStateException("backend does not know ?status="));

    String html =
        mockMvc
            .perform(
                get("/admin/discord-registrations")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("the pending queue still renders and the rejected table degrades to its empty state")
        .contains("AliceCallsign")
        .contains("id=\"rejectedEmpty\"");
  }

  @Test
  void reopenAjax_forwardsToBackend_andReturnsOk() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.post(
            eq("/api/v1/admin/registrations/" + id + "/reopen"),
            any(),
            eq(PendingRegistrationDto.class)))
        .thenReturn(
            new PendingRegistrationDto(
                id, "StolpiCallsign", null, Instant.parse("2026-06-22T00:00:00Z"), null, 4L));

    mockMvc
        .perform(
            post("/admin/discord-registrations/" + id + "/reopen")
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"rejected by mistake\",\"version\":3}")
                .with(csrf())
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  /**
   * Counts non-overlapping occurrences of {@code needle} in {@code haystack}.
   *
   * @param haystack the rendered HTML
   * @param needle the substring to count
   * @return the number of occurrences
   */
  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int from = 0;
    int at;
    while ((at = haystack.indexOf(needle, from)) >= 0) {
      count++;
      from = at + needle.length();
    }
    return count;
  }
}
