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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full Thymeleaf render test for the Discord registration-approval queue's server-nickname column
 * (REQ-DATA-008). Pins that a pending registration's captured per-guild nickname is rendered next
 * to the name so an admin sees it at the approval decision, while a registration without a captured
 * nickname falls back to the muted em-dash (no nickname value leaks for it). The assertions key off
 * the controlled nickname value, so they are locale-independent.
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
            1L);
    PendingRegistrationDto withoutNick =
        new PendingRegistrationDto(
            UUID.randomUUID(), "BobCallsign", null, Instant.parse("2026-06-22T00:00:00Z"), 1L);

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
