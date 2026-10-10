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

package de.greluc.krt.profit.basetool.frontend.audit.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.audit.model.AuditEventDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
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
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
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
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
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

  /**
   * The viewer renders on the list pattern (REQ-UI-027): page head with the system eyebrow and no
   * primary action, export and purge in the overflow menu, the live toolbar without a submit
   * button, and the stacked table with the translated event label.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheListPattern() throws Exception {
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
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.of(row), 0, 50, 1, 1, List.of()));

    String html = render("INVENTORY");

    String head =
        html.substring(
            html.indexOf("data-testid=\"page-head\""), html.indexOf("id=\"audit-results\""));
    assertThat(head)
        .containsPattern("class=\"page-eyebrow\"[^>]*>System &amp; Daten<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"audit-results\"")
        .contains("data-overflow-menu")
        .contains("data-testid=\"audit-export-open\"")
        .contains("data-testid=\"audit-purge-open\"")
        .contains("data-testid=\"audit-filter-event\"")
        .contains("data-testid=\"audit-filter-toggle\"")
        .contains("data-filter-chips")
        .doesNotContain("data-testid=\"audit-filter-apply\"");
    assertThat(head.split("btn--cta", -1).length).isLessThanOrEqualTo(2);
    assertThat(html).doesNotContain("class=\"greeting").doesNotContain("hud-box");
    assertThat(html)
        .contains("class=\"card card--flush\" data-testid=\"audit-panel\"")
        .contains("class=\"data-table data-table--stack\"")
        .contains("data-testid=\"audit-row\"")
        .containsPattern("class=\"cell-title\">Lager-Eintrag angelegt<")
        .doesNotContain(">INVENTORY_ITEM_CREATED<")
        .contains("data-list-total=\"1\"")
        .contains("class=\"alert alert-warning audit-purge-warning\"");
  }

  /**
   * An empty result renders the empty state instead of a table.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheEmptyState() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    String html = render("INVENTORY");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("id=\"audit-empty\"")
        .contains("Keine Ereignisse für die aktuellen Filter.")
        .doesNotContain("data-table--stack")
        .doesNotContain("data-list-total");
  }

  /**
   * Renders the viewer for one tab in German.
   *
   * @param domain the tab to open
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private String render(String domain) throws Exception {
    return mockMvc
        .perform(get("/admin/audit-log").param("domain", domain).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
