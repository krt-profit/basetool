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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full Thymeleaf render test for the member-edit page: it renders on a plain GET and emits the
 * {@code data-member-edit} gate and the {@code data-error-for} slots for per-field errors
 * (REQ-FE-007).
 */
@SpringBootTest
class MemberEditPageRenderTest {

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
  @WithMockUser(roles = "ADMIN")
  void editPage_rendersGetSafeFieldErrorSlotsAndInPlaceGate() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/users/" + id), eq(UserDto.class)))
        .thenReturn(
            new UserDto(
                id,
                "pilot",
                "Pilot",
                "Pilot",
                "pilot@example.com",
                5,
                "desc",
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
                false));

    String html =
        mockMvc
            .perform(get("/members/{id}/edit", id))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("in-place save gate present").contains("data-member-edit=\"true\"");
    assertThat(html)
        .as("displayName error slot present + GET-safe (no EL1011E thrown)")
        .contains("data-error-for=\"displayName\"");
    assertThat(html)
        .as("description error slot present")
        .contains("data-error-for=\"description\"");
  }
}
