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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class ProfileControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void profile_ShouldSetMonthsInSquadron_WhenJoinDateIsPresent() throws Exception {
    // Given
    LocalDate joinDate = LocalDate.now().minusMonths(14);
    long expectedMonths = ChronoUnit.MONTHS.between(joinDate, LocalDate.now());

    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenReturn(
            Map.of(
                "rank", "Pilot",
                "description", "Test",
                "displayName", "TestUser",
                "version", 1L,
                "joinDate", joinDate.toString()));

    // When & Then
    mockMvc
        .perform(get("/profile").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("profile"))
        .andExpect(model().attribute("monthsInSquadron", expectedMonths));
  }

  @Test
  void profile_ShouldNotSetMonthsInSquadron_WhenJoinDateIsAbsent() throws Exception {
    // Given
    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef()))
        .thenReturn(
            Map.of(
                "rank", "Pilot",
                "description", "Test",
                "displayName", "TestUser",
                "version", 1L));

    // When & Then
    mockMvc
        .perform(get("/profile").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("profile"))
        .andExpect(model().attributeDoesNotExist("monthsInSquadron"));
  }

  @Test
  void profile_ShouldNotSetMonthsInSquadron_WhenJoinDateIsNull() throws Exception {
    // Given
    Map<String, Object> userMap = new HashMap<>();
    userMap.put("rank", "Pilot");
    userMap.put("description", "Test");
    userMap.put("displayName", "TestUser");
    userMap.put("version", 1L);
    userMap.put("joinDate", null);

    when(backendApiClient.get(eq("/api/v1/users/me"), anyTypeRef())).thenReturn(userMap);

    // When & Then
    mockMvc
        .perform(get("/profile").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("profile"))
        .andExpect(model().attributeDoesNotExist("monthsInSquadron"));
  }
}
