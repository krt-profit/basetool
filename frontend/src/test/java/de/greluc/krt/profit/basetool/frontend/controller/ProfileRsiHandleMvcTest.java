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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MyRsiHandleDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the RSI-handle card of the profile page (REQ-SEC-072): the rendered value, the in-place
 * AJAX save with its validation and relayed conflicts, and the no-script form post.
 */
@SpringBootTest
class ProfileRsiHandleMvcTest {

  private static final String BACKEND = "/api/v1/users/me/rsi-handle";

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void theCardShowsTheStoredHandle() throws Exception {
    when(backendApiClient.get(eq(BACKEND), anyTypeRef()))
        .thenReturn(Map.of("rsiHandle", "Stored_Handle", "version", 4));

    mockMvc
        .perform(get("/profile").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("id=\"profile-rsi-handle-form\"")))
        .andExpect(content().string(Matchers.containsString("value=\"Stored_Handle\"")));
  }

  @SuppressWarnings("unchecked")
  @Test
  void anAjaxSaveRelaysTheTrimmedHandleAndAnswersWithTheStoredOne() throws Exception {
    when(backendApiClient.put(eq(BACKEND), any(), eq(MyRsiHandleDto.class)))
        .thenReturn(new MyRsiHandleDto("New_Handle", 5L));

    mockMvc
        .perform(
            post("/profile/rsi-handle")
                .with(oidcLogin())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rsiHandle\":\"  New_Handle \",\"version\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rsiHandle").value("New_Handle"))
        .andExpect(jsonPath("$.version").value(5));

    ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
    verify(backendApiClient).put(eq(BACKEND), body.capture(), eq(MyRsiHandleDto.class));
    assertThat(body.getValue())
        .containsEntry("rsiHandle", "New_Handle")
        .containsEntry("version", 4L);
  }

  @Test
  void anAjaxSaveOfAMalformedHandleIsRefusedBeforeTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/profile/rsi-handle")
                .with(oidcLogin())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rsiHandle\":\"no spaces allowed\",\"version\":4}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).put(anyString(), any(), eq(MyRsiHandleDto.class));
  }

  @Test
  void aTakenHandleRelaysTheBackendConflictAndItsCode() throws Exception {
    when(backendApiClient.put(eq(BACKEND), any(), eq(MyRsiHandleDto.class)))
        .thenThrow(
            new BackendServiceException(
                "taken", null, 409, "DUPLICATE_ENTITY", null, List.of(), "Already taken."));

    mockMvc
        .perform(
            post("/profile/rsi-handle")
                .with(oidcLogin())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rsiHandle\":\"Taken_Handle\",\"version\":4}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_ENTITY"));
  }

  @Test
  void theNoScriptFormPostSavesAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/profile/rsi-handle")
                .with(oidcLogin())
                .with(csrf())
                .param("rsiHandle", "Form_Handle")
                .param("version", "2"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/profile"))
        .andExpect(flash().attribute("successToast", "notification.success.save"));

    verify(backendApiClient)
        .put(eq(BACKEND), eq(Map.of("rsiHandle", "Form_Handle", "version", 2L)), eq(Void.class));
  }

  @Test
  void theNoScriptFormPostShowsTheTakenToastOnAConflict() throws Exception {
    when(backendApiClient.put(eq(BACKEND), any(), eq(Void.class)))
        .thenThrow(
            new BackendServiceException(
                "taken", null, 409, "DUPLICATE_ENTITY", null, List.of(), "Already taken."));

    mockMvc
        .perform(
            post("/profile/rsi-handle")
                .with(oidcLogin())
                .with(csrf())
                .param("rsiHandle", "Taken_Handle")
                .param("version", "2"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorToast", "profile.rsiHandle.taken"));
  }
}
