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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * Renders the bank staff pages on the area pattern (REQ-UI-027): the management page with one
 * primary action per tab, account rows with their lifecycle actions in the overflow menu and the
 * KRT tier bar; the dashboard with the role hint as eyebrow and its view toolbar; the grants page
 * with its view segment.
 */
@SpringBootTest
class BankStaffPagesPatternRenderTest {

  private static final String ACCOUNTS_URI = "/api/v1/bank/accounts?page=0&size=25&sort=name,asc";

  private static final String CARTEL_URI = "/api/v1/bank/accounts?type=CARTEL&size=1";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders a path in German.
   *
   * @param path the path including its query string
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull String path) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get(path).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The markup of the page head, up to the element that follows it.
   *
   * @param html the rendered page
   * @param end a marker of the first element after the page head
   * @return the page-head markup
   */
  private static @NotNull String pageHead(@NotNull String html, @NotNull String end) {
    int start = html.indexOf("data-testid=\"page-head\"");
    assertThat(start).isNotNegative();
    int stop = html.indexOf(end, start);
    assertThat(stop).isGreaterThan(start);
    return html.substring(start, stop);
  }

  /**
   * A bank account as the management listing returns it.
   *
   * @param type the account type
   * @param status the account status
   * @param balance the balance
   * @param t1 the employee ceiling, or {@code null}
   * @param t2 the bank-management ceiling, or {@code null}
   * @return the account
   */
  private static @NotNull BankAccountDto account(
      @NotNull String type,
      @NotNull String status,
      @NotNull String balance,
      @Nullable String t1,
      @Nullable String t2) {
    return new BankAccountDto(
        UUID.randomUUID(),
        "KB-0001",
        "Staffel IRIDIUM",
        type,
        status,
        null,
        null,
        new BigDecimal(balance),
        null,
        t1 == null ? null : new BigDecimal(t1),
        t2 == null ? null : new BigDecimal(t2),
        0L,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  /** The accounts tab has one primary action and the lifecycle actions in a row menu. */
  @Test
  @WithMockUser(roles = "BANK_MANAGEMENT")
  void manageAccountsTabRendersRowMenusAndOnePrimaryAction() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(ACCOUNTS_URI), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(
                    account("ORG_UNIT", "ACTIVE", "0", null, null),
                    account("SPECIAL", "CLOSED", "0", null, null)),
                0,
                25,
                2L,
                1,
                List.of()));

    String html = render("/bank/manage?tab=konten");
    String head = pageHead(html, "id=\"bank-manage-results\"");

    assertThat(head.split("btn--cta", -1)).hasSize(2);
    assertThat(head)
        .contains("data-testid=\"bank-create-account-open\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*href=\"/bank\"");
    assertThat(html)
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .contains("class=\"data-table data-table--stack\"")
        .contains("data-testid=\"row-link\"")
        .contains("data-testid=\"overflow-menu-toggle\"")
        .contains("data-testid=\"bank-rename-open\"")
        .contains("data-testid=\"bank-target-open\"")
        .contains("data-testid=\"bank-close-open\"")
        .contains("data-testid=\"bank-reopen-open\"")
        .contains("id=\"bank-create-account-modal\"")
        .contains("id=\"bank-rename-modal\"")
        .contains("id=\"bank-target-modal\"")
        .contains("id=\"bank-close-modal\"")
        .contains("id=\"bank-reopen-modal\"")
        .contains("id=\"bank-register-holder-modal\"")
        .contains("id=\"bank-holder-deactivate-modal\"")
        .contains("id=\"bank-holder-reactivate-modal\"")
        .contains("id=\"bank-holder-transfer-modal\"")
        .contains(">Aktiv<")
        .doesNotContain(">ACTIVE<");
  }

  /** The holders tab offers the custody transfer as its primary action and registration beside. */
  @Test
  @WithMockUser(roles = "BANK_MANAGEMENT")
  void manageHoldersTabRendersTransferAsThePrimaryAction() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef()))
        .thenReturn(
            List.of(
                new BankHolderDto(
                    UUID.randomUUID(), null, "greluc", true, new BigDecimal("1000"), false, 0L)));

    String html = render("/bank/manage");
    String head = pageHead(html, "id=\"bank-manage-results\"");

    assertThat(head.split("btn--cta", -1)).hasSize(2);
    assertThat(head)
        .contains("data-testid=\"bank-holder-transfer-open\"")
        .contains("data-testid=\"bank-register-holder-open\"");
    assertThat(html)
        .contains("data-testid=\"bank-holder-registry-row\"")
        .contains("data-testid=\"bank-holder-deactivate-open\"");
  }

  /** The KRT tab draws the three-tier bar, both thresholds and the example. */
  @Test
  @WithMockUser(roles = "BANK_MANAGEMENT")
  void manageKrtTabRendersTheTierBar() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(CARTEL_URI), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(account("CARTEL", "ACTIVE", "5000000", "250000", "2000000")),
                0,
                1,
                1L,
                1,
                List.of()));

    String html = render("/bank/manage?tab=krt-freigaben");
    String head = pageHead(html, "id=\"bank-manage-results\"");

    assertThat(head).doesNotContain("btn--cta");
    assertThat(html)
        .contains("data-testid=\"bank-krt-tier-bar\"")
        .contains("Wer gibt Auszahlungen vom KRT-Konto frei?")
        .containsPattern("data-tier-range=\"employee\"[^>]*>bis 250.000<")
        .containsPattern("data-tier-range=\"management\"[^>]*>bis 2.000.000<")
        .containsPattern("data-tier-range=\"ol\"[^>]*>darüber<")
        .contains("value=\"250000\"")
        .contains("value=\"2000000\"")
        .contains("class=\"alert alert-info bank-tiers__example\"")
        .contains(
            "Beispiel: Eine Auszahlung über 250.000 aUEC braucht die Freigabe der Bankleitung.")
        .contains("Über 2.000.000 aUEC gibt die Organisationsleitung frei.")
        .containsPattern("data-testid=\"bank-krt-approvals-save\"[^>]*>Schwellen speichern<");
  }

  /** The dashboard shows the role hint as eyebrow and the view options in the toolbar. */
  @Test
  @WithMockUser(roles = "BANK_MANAGEMENT")
  void dashboardRendersTheRoleHintEyebrowAndToolbar() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(
            new BankDashboardDto(
                true,
                List.of(
                    new BankDashboardAccountDto(
                        UUID.randomUUID(),
                        "KB-0001",
                        "Staffel IRIDIUM",
                        "ORG_UNIT",
                        "ACTIVE",
                        new BigDecimal("1000"),
                        BigDecimal.ZERO,
                        List.of(),
                        null,
                        null,
                        null)),
                null));

    String html = render("/bank");

    assertThat(html)
        .containsPattern("class=\"page-eyebrow\"[^>]*>Ansicht: Bankleitung · 1 Konten<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .contains("class=\"toolbar\"")
        .contains("class=\"switch\" for=\"bank-view-layout\"")
        .contains("data-bank-view-group");
  }

  /** The grants page carries its views as a segment in the toolbar and an empty state. */
  @Test
  @WithMockUser(roles = "BANK_MANAGEMENT")
  void grantsRendersTheViewSegmentAndEmptyState() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);

    String html = render("/bank/grants?view=employee");

    assertThat(html)
        .contains("class=\"page-head\"")
        .contains("data-testid=\"bank-grant-create-open\"")
        .contains("data-testid=\"segment-view-employee\"")
        .containsPattern("name=\"view\" value=\"employee\" checked=\"checked\"")
        .contains("id=\"bank-grants-user-filter\"")
        .contains("id=\"bank-grants-empty\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
  }
}
