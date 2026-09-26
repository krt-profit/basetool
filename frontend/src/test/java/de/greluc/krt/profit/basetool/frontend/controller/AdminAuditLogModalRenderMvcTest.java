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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.AuditEventDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StringUtils;
import org.springframework.web.context.WebApplicationContext;

/**
 * Render test for {@code /admin/audit-log}: the export modal renders its shell through {@code
 * fragments/modal-wrapper :: modal(...)} and projects its {@code <form>} body exactly once, and the
 * originating-client filter and column render (REQ-AUDIT-005).
 */
@SpringBootTest
class AdminAuditLogModalRenderMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void exportModal_rendersViaFragmentShell_andProjectsFormExactlyOnce() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    String html =
        mockMvc
            .perform(get("/admin/audit-log").param("domain", "BANK"))
            .andExpect(status().isOk())
            .andExpect(view().name("admin/audit-log"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("id=\"audit-export-modal\"");
    assertThat(html).contains("class=\"krt-modal-head\"");
    assertThat(html)
        .contains("class=\"krt-modal-close\"")
        .contains("data-trigger=\"close-modal-display\"")
        .contains("data-modal-id=\"audit-export-modal\"");
    assertThat(html).contains("data-testid=\"audit-export-submit\"");
    assertThat(StringUtils.countOccurrencesOf(html, "audit-download-form")).isEqualTo(1);

    assertThat(html).contains("id=\"audit-purge-modal\"");
    assertThat(html).contains("data-modal-id=\"audit-purge-modal\"");
    assertThat(html).contains("data-testid=\"audit-purge-submit\"");
    assertThat(StringUtils.countOccurrencesOf(html, "audit-purge-form")).isEqualTo(1);

    assertThat(html).doesNotContain("data-modal-dismiss");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void genericTab_rendersTheClientFilterAndColumn() throws Exception {
    AuditEventDto row =
        new AuditEventDto(
            UUID.randomUUID(),
            Instant.parse("2026-09-02T10:15:00Z"),
            "INVENTORY",
            "INVENTORY_ITEM_CREATED",
            "logi_jo",
            UUID.randomUUID(),
            "Quantanium @ Port Olisar",
            null,
            "qty=5.0",
            "basetool-android");
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(row), 0, 50, 1, 1, List.of()));

    String html =
        mockMvc
            .perform(get("/admin/audit-log").param("domain", "INVENTORY"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("data-testid=\"audit-filter-client\"");
    assertThat(html).contains("value=\"basetool-android\"");
    assertThat(html).doesNotContain("??admin.audit.client.");
    assertThat(html).contains("data-testid=\"audit-row-client\"");
    assertThat(html).contains("Android-App");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void bankTab_rendersTheClientFilterAndColumnToo() {
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    String html =
        assertDoesNotThrow(
            () ->
                mockMvc
                    .perform(get("/admin/audit-log").param("domain", "BANK"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());

    assertThat(html).contains("data-testid=\"audit-filter-client\"");
    assertThat(html).doesNotContain("??admin.audit.client.");
  }
}
