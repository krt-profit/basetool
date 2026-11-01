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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardColumn;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests of the dashboard write relay: the layout, the options of one instance and the restore of
 * the default reach the backend, a stale version surfaces as 409 and an incomplete payload as 422
 * (REQ-UI-034).
 */
@SpringBootTest
class DashboardLayoutControllerTest {

  private static final String DASHBOARD = "/api/v1/navigation/dashboards/{mode}";
  private static final String XHR = "X-Requested-With";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static RequestPostProcessor member() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  @Test
  @DisplayName("a layout write is relayed and the stored dashboard returned")
  void relaysTheLayout() throws Exception {
    when(backendApiClient.put(
            eq(DASHBOARD + "/layout"), any(), eq(DashboardResponse.class), eq("member")))
        .thenReturn(
            new DashboardResponse(
                DashboardMode.MEMBER,
                1,
                true,
                List.of(
                    new DashboardWidgetResponse(
                        UUID.randomUUID(),
                        DashboardWidgetType.FAVOURITES,
                        DashboardColumn.NARROW,
                        0,
                        null,
                        null,
                        null,
                        null))));

    mockMvc
        .perform(
            put("/dashboard/member/layout")
                .header(XHR, "XMLHttpRequest")
                .with(member())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"widgets\":[{\"type\":\"FAVOURITES\",\"column\":\"NARROW\"}],\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.widgets[0].type").value("FAVOURITES"));
  }

  @Test
  @DisplayName("a stale layout version surfaces as the backend's 409")
  void relaysAConflict() throws Exception {
    when(backendApiClient.put(
            eq(DASHBOARD + "/layout"), any(), eq(DashboardResponse.class), eq("member")))
        .thenThrow(new BackendServiceException("conflict", null, 409));

    mockMvc
        .perform(
            put("/dashboard/member/layout")
                .header(XHR, "XMLHttpRequest")
                .with(member())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"widgets\":[],\"version\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("an incomplete payload and an unknown mode are refused before the backend")
  void refusesIncompleteAndUnknown() throws Exception {
    mockMvc
        .perform(
            put("/dashboard/member/layout")
                .header(XHR, "XMLHttpRequest")
                .with(member())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"widgets\":[]}"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION"));
    mockMvc
        .perform(
            put("/dashboard/guest/layout")
                .header(XHR, "XMLHttpRequest")
                .with(member())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"widgets\":[],\"version\":0}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("an options write is relayed with the instance id")
  void relaysTheOptions() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.put(
            eq(DASHBOARD + "/widgets/{widgetId}/options"),
            any(),
            eq(DashboardWidgetResponse.class),
            eq("member"),
            eq(id)))
        .thenReturn(
            new DashboardWidgetResponse(
                id,
                DashboardWidgetType.UPCOMING_DAYS,
                DashboardColumn.WIDE,
                1,
                null,
                14,
                10,
                null));

    mockMvc
        .perform(
            put("/dashboard/member/widgets/" + id + "/options")
                .header(XHR, "XMLHttpRequest")
                .with(member())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"days\":14,\"rows\":10,\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.optionsVersion").value(1));
  }

  @Test
  @DisplayName("restoring the default is relayed with the echoed version")
  void relaysTheReset() throws Exception {
    when(backendApiClient.delete(
            eq(DASHBOARD + "?version={version}"),
            eq(DashboardResponse.class),
            eq("member"),
            eq(3L)))
        .thenReturn(new DashboardResponse(DashboardMode.MEMBER, 4, false, List.of()));

    mockMvc
        .perform(
            delete("/dashboard/member")
                .param("version", "3")
                .header(XHR, "XMLHttpRequest")
                .with(member())
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.customised").value(false));
  }
}
