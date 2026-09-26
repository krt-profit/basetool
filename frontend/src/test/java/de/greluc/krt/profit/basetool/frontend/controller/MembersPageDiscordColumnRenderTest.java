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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Set;
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
 * Full Thymeleaf render test for the member-list Discord column (REQ-SEC-019): only a
 * Discord-linked member's row carries the Discord icon, and the column header is rendered.
 */
@SpringBootTest
class MembersPageDiscordColumnRenderTest {

  /** The {@code <use>} reference emitted only where the Discord icon is actually rendered. */
  private static final String DISCORD_ICON_USE = "href=\"#krt-icon-discord\"";

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
  void memberList_showsDiscordIconOnlyForLinkedMember() throws Exception {
    UserDto linked = user("AliceLinked", Boolean.TRUE);
    UserDto notLinked = user("BobUnlinked", Boolean.FALSE);
    PageResponse<UserDto> page =
        new PageResponse<>(List.of(linked, notLinked), 0, 20, 2, 1, List.of("username,asc"));

    when(backendApiClient.get(eq("/api/v1/users?sort=username,asc"), anyTypeRef()))
        .thenReturn(page);
    when(backendApiClient.get(contains("/memberships"), anyTypeRef())).thenReturn(List.of());

    String html =
        mockMvc
            .perform(
                get("/members")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("Discord column header rendered").contains(">Discord</th>");
    assertThat(html)
        .as("both member rows rendered")
        .contains("AliceLinked")
        .contains("BobUnlinked");
    assertThat(countOccurrences(html, DISCORD_ICON_USE))
        .as("exactly the one linked member shows the Discord icon")
        .isEqualTo(1);
  }

  /**
   * Verifies that the consolidate action and its dialog are offered on every member row, not only
   * where the delete button appears (REQ-SEC-055).
   */
  @Test
  void memberList_offersConsolidateOnEveryRow_andRendersTheDialog() throws Exception {
    UserDto present = user("AlicePresent", Boolean.FALSE);
    UserDto duplicate = user("AliceDuplicate", Boolean.TRUE);
    PageResponse<UserDto> page =
        new PageResponse<>(List.of(present, duplicate), 0, 20, 2, 1, List.of("username,asc"));

    when(backendApiClient.get(eq("/api/v1/users?sort=username,asc"), anyTypeRef()))
        .thenReturn(page);
    when(backendApiClient.get(contains("/memberships"), anyTypeRef())).thenReturn(List.of());

    String html =
        mockMvc
            .perform(
                get("/members")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(countOccurrences(html, "data-trigger=\"members-consolidate\""))
        .as("one consolidate action per member row")
        .isEqualTo(2);
    assertThat(html)
        .as("the dialog and its server-searched account picker are rendered")
        .contains("id=\"consolidate-modal\"")
        .contains("id=\"consolidate-target\"")
        .contains("data-krt-combobox=\"remote-users\"");
    assertThat(html)
        .as("the row carries the optimistic-lock version the action echoes back")
        .contains("data-user-version=");
  }

  /**
   * Builds a minimal member DTO with the fields the row template reads and the {@code
   * discordLinked} indicator.
   *
   * @param effectiveName the visible name, also the row marker in assertions
   * @param discordLinked whether the member is Discord-linked
   * @return the populated DTO
   */
  private static UserDto user(String effectiveName, Boolean discordLinked) {
    return new UserDto(
        UUID.randomUUID(),
        effectiveName,
        effectiveName,
        effectiveName,
        null,
        5,
        null,
        Set.of("ROLE_KRT_MEMBER"),
        Set.of(),
        null,
        false,
        false,
        true,
        null,
        java.util.List.of(),
        1L,
        null,
        discordLinked);
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
