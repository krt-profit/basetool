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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBulkDeleteResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the personal-blueprint write twins ({@link
 * PersonalInventoryBlueprintsPageController#updateNoteAjax}/{@code deleteAjax}): a note edit
 * returns the fresh blueprint as JSON, an optimistic-lock failure is relayed as {@code 409} {@code
 * problem+json} with its {@code code}, and a remove answers {@code 204}.
 */
@SpringBootTest
class PersonalBlueprintWriteAjaxControllerTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser
  void updateNoteAjax_valid_returnsFreshBlueprintJson() throws Exception {
    UUID id = UUID.randomUUID();
    PersonalBlueprintDto fresh =
        new PersonalBlueprintDto(
            id, "key", "Quantum Drive", null, null, "updated note", true, 3L, null, null);
    when(backendApiClient.put(
            eq("/api/v1/personal-blueprints/" + id), any(), eq(PersonalBlueprintDto.class)))
        .thenReturn(fresh);

    mockMvc
        .perform(
            post("/personal-inventory/blueprints/" + id + "/update-note")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"updated note\",\"acquiredAt\":null,\"version\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.note").value("updated note"));
  }

  @Test
  @WithMockUser
  void updateNoteAjax_backendOptimisticLock_propagatesProblemJsonWithCode() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.put(
            eq("/api/v1/personal-blueprints/" + id), any(), eq(PersonalBlueprintDto.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, Collections.emptyList(), null));

    mockMvc
        .perform(
            post("/personal-inventory/blueprints/" + id + "/update-note")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\",\"acquiredAt\":null,\"version\":2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser
  void deleteAjax_valid_forwardsToBackendAndReturnsNoContent() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/personal-inventory/blueprints/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isNoContent());

    verify(backendApiClient).delete(eq("/api/v1/personal-blueprints/" + id), eq(Void.class));
  }

  @Test
  @WithMockUser
  void deleteAllAjax_forwardsToBackendAndReturnsRemovedCount() throws Exception {
    when(backendApiClient.delete(
            eq("/api/v1/personal-blueprints"), eq(PersonalBlueprintBulkDeleteResultDto.class)))
        .thenReturn(new PersonalBlueprintBulkDeleteResultDto(5));

    mockMvc
        .perform(
            post("/personal-inventory/blueprints/delete-all")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(5));

    verify(backendApiClient)
        .delete(eq("/api/v1/personal-blueprints"), eq(PersonalBlueprintBulkDeleteResultDto.class));
  }
}
