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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
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
 * MVC test for {@link AdminSettingsPageController}'s settings AJAX twin: it applies the invariants
 * and per-setting PUTs and returns the new versions as JSON, a yellow &gt;= red violation returns
 * {@code 422 problem+json}, and without {@code X-Requested-With} the URL still redirects.
 */
@SpringBootTest
class AdminSettingsPageControllerMvcTest {

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

  /** Stubs every per-setting PUT to return a setting carrying a bumped version. */
  private void stubAllPuts() {
    when(backendApiClient.put(contains("/settings/"), any(), eq(SystemSettingDto.class)))
        .thenReturn(new SystemSettingDto("k", "v", 1L));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void updateSettingsAjax_withHeader_returns200WithBumpedVersions() throws Exception {
    stubAllPuts();

    mockMvc
        .perform(
            post("/admin/settings")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"ageYellowDays\":\"30\",\"ageYellowVersion\":0,"
                        + "\"ageRedDays\":\"90\",\"ageRedVersion\":0,"
                        + "\"refineryRoundingMode\":\"UP\",\"refineryRoundingVersion\":0,"
                        + "\"transferFeePercent\":\"0.5\",\"transferFeeVersion\":0}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ageYellowVersion")))
        .andExpect(content().string(containsString("transferFeeVersion")));

    verify(backendApiClient).clearStaticDataCache();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void updateSettingsAjax_withHeaderYellowGteRed_returns422() throws Exception {
    mockMvc
        .perform(
            post("/admin/settings")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"ageYellowDays\":\"90\",\"ageYellowVersion\":0,"
                        + "\"ageRedDays\":\"30\",\"ageRedVersion\":0,"
                        + "\"refineryRoundingMode\":\"UP\",\"refineryRoundingVersion\":0,"
                        + "\"transferFeePercent\":\"0.5\",\"transferFeeVersion\":0}"))
        .andExpect(status().isUnprocessableContent());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void updateSettings_withoutHeader_redirects() throws Exception {
    stubAllPuts();

    mockMvc
        .perform(
            post("/admin/settings")
                .with(csrf())
                .param("ageYellowDays", "30")
                .param("ageYellowVersion", "0")
                .param("ageRedDays", "90")
                .param("ageRedVersion", "0")
                .param("refineryRoundingMode", "UP")
                .param("refineryRoundingVersion", "0")
                .param("transferFeePercent", "0.5")
                .param("transferFeeVersion", "0"))
        .andExpect(status().is3xxRedirection());

    verify(backendApiClient).clearStaticDataCache();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void viewSettings_ShouldExcludeCheckboxesFromFormGroupInputRule() throws Exception {
    mockMvc
        .perform(get("/admin/settings"))
        .andExpect(status().isOk())
        .andExpect(
            PageStylesheets.content(
                containsString(
                    ".form-group input:where(:not([type='checkbox']):not([type='radio']))")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void updateSettingsAjax_partialSaveFailure_stillEvictsStaticCache() throws Exception {
    stubAllPuts();
    when(backendApiClient.put(
            eq("/api/v1/settings/job_order.age_red_days"), any(), eq(SystemSettingDto.class)))
        .thenThrow(new RuntimeException("backend down mid-save"));

    mockMvc
        .perform(
            post("/admin/settings")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"ageYellowDays\":\"30\",\"ageYellowVersion\":0,"
                        + "\"ageRedDays\":\"90\",\"ageRedVersion\":0,"
                        + "\"refineryRoundingMode\":\"UP\",\"refineryRoundingVersion\":0,"
                        + "\"transferFeePercent\":\"0.5\",\"transferFeeVersion\":0}"))
        .andExpect(status().is5xxServerError());

    verify(backendApiClient).clearStaticDataCache();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void updateSettings_partialSaveFailure_stillEvictsStaticCache() throws Exception {
    stubAllPuts();
    when(backendApiClient.put(
            eq("/api/v1/settings/job_order.age_red_days"), any(), eq(SystemSettingDto.class)))
        .thenThrow(new RuntimeException("backend down mid-save"));

    mockMvc
        .perform(
            post("/admin/settings")
                .with(csrf())
                .param("ageYellowDays", "30")
                .param("ageYellowVersion", "0")
                .param("ageRedDays", "90")
                .param("ageRedVersion", "0")
                .param("refineryRoundingMode", "UP")
                .param("refineryRoundingVersion", "0")
                .param("transferFeePercent", "0.5")
                .param("transferFeeVersion", "0"))
        .andExpect(status().is3xxRedirection());

    verify(backendApiClient).clearStaticDataCache();
  }
}
