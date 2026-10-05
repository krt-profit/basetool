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

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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

  private static UserDto me(LocalDate joinDate) {
    return new UserDto(
        UUID.fromString("7d3e2f1a-6b5c-4d4e-8f9a-0b1c2d3e4f5a"),
        "testuser",
        "TestUser",
        "TestUser",
        null,
        3,
        "Test",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        1L,
        joinDate,
        null);
  }

  @Test
  void profile_ShouldSetMonthsInSquadron_WhenJoinDateIsPresent() throws Exception {
    LocalDate joinDate = LocalDate.now().minusMonths(14);
    long expectedMonths = ChronoUnit.MONTHS.between(joinDate, LocalDate.now());

    when(backendApiClient.get("/api/v1/users/me", UserDto.class)).thenReturn(me(joinDate));

    mockMvc
        .perform(get("/profile").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("profile"))
        .andExpect(model().attribute("monthsInSquadron", expectedMonths));
  }

  @Test
  void profile_ShouldNotSetMonthsInSquadron_WhenJoinDateIsNull() throws Exception {
    when(backendApiClient.get("/api/v1/users/me", UserDto.class)).thenReturn(me(null));

    mockMvc
        .perform(get("/profile").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("profile"))
        .andExpect(model().attributeDoesNotExist("monthsInSquadron"));
  }
}
