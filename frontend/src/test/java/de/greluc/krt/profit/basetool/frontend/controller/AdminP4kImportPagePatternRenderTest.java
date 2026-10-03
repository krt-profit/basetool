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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders {@code /admin/p4k-import} on the list pattern (REQ-UI-027): page head under the
 * "Stammdaten" eyebrow, the upload and the job list as cards, the stacked job table whose rows the
 * page script fills, and the empty-state hook the script toggles.
 */
@SpringBootTest
class AdminP4kImportPagePatternRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /** The page carries the list-pattern frame and keeps every hook {@code p4k-import.js} reads. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheListPattern() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String html =
        mockMvc
            .perform(get("/admin/p4k-import").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Stammdaten<")
        .contains("<h1>P4K-Import</h1>")
        .doesNotContain("data-testid=\"page-head-count\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("krt-admin-banner")
        .doesNotContain("hud-box");
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main.split("btn--cta", -1)).hasSize(3);
    assertThat(main)
        .contains("id=\"krt-p4k-upload-btn\" class=\"btn btn--cta\"")
        .contains("id=\"krt-p4k-apply-confirm\" class=\"btn btn--cta\"")
        .contains("class=\"data-table data-table--stack\"")
        .contains("<tbody id=\"krt-p4k-jobs\">")
        .containsPattern(
            "id=\"krt-p4k-jobs-empty\" class=\"empty-state\" data-testid=\"empty-state\" hidden")
        .contains("id=\"krt-p4k-apply-panel\"")
        .contains("id=\"krt-p4k-results\"");
    assertThat(html).contains("/css/pages/admin-p4k-import.css").contains("/js/p4k-import.js");
  }
}
