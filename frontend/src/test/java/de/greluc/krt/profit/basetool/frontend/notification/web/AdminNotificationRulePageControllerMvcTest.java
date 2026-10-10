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

package de.greluc.krt.profit.basetool.frontend.notification.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.notification.model.NotificationRuleDto;
import de.greluc.krt.profit.basetool.frontend.notification.model.NotificationRuleSelectorDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Locale;
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
 * MVC render test for {@link AdminNotificationRulePageController} (REQ-NOTIF-007): the model's
 * option lists offer every selector kind, and the {@code fragment=rules} swap renders only the
 * rules table while an unknown fragment value renders the whole page (REQ-FE-001).
 */
@SpringBootTest
class AdminNotificationRulePageControllerMvcTest {

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

  /**
   * Stubs the backend with two rules: a seeded bank rule carrying the event-derived {@code
   * ACCOUNT_RESPONSIBLE} selector, and one whose event type is newer than this page.
   */
  private void stubRules() {
    NotificationRuleDto bankRule =
        new NotificationRuleDto(
            UUID.randomUUID(),
            "BANK_BOOKING_REQUEST_CREATED",
            "BANK_BOOKING_REQUEST_CREATED",
            "seeded bank rule",
            true,
            true,
            0L,
            null,
            null,
            List.of(
                new NotificationRuleSelectorDto(
                    UUID.randomUUID(), "ACCOUNT_RESPONSIBLE", null, null, null, null)));
    NotificationRuleDto futureRule =
        new NotificationRuleDto(
            UUID.randomUUID(),
            "SOME_FUTURE_EVENT",
            "SOME_FUTURE_TYPE",
            null,
            false,
            true,
            0L,
            null,
            null,
            List.of());
    when(backendApiClient.get(contains("/api/v1/notification-rules"), anyTypeRef()))
        .thenReturn(List.of(bankRule, futureRule));
  }

  /** The full page carries every option list, in the controller's order, and the swap host. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void pagePutsEveryOptionListOnTheModel() throws Exception {
    stubRules();

    mockMvc
        .perform(get("/admin/notification-rules"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/notification-rules"))
        .andExpect(model().attribute("eventTypes", AdminNotificationRulePageController.EVENT_TYPES))
        .andExpect(
            model()
                .attribute(
                    "notificationTypes", AdminNotificationRulePageController.NOTIFICATION_TYPES))
        .andExpect(
            model().attribute("selectorKinds", AdminNotificationRulePageController.SELECTOR_KINDS))
        .andExpect(
            model()
                .attribute(
                    "orgRelativeRoles", AdminNotificationRulePageController.ORG_RELATIVE_ROLES))
        .andExpect(
            model().attribute("contextRoles", AdminNotificationRulePageController.CONTEXT_ROLES))
        .andExpect(model().attribute("roleCodes", AdminNotificationRulePageController.ROLE_CODES))
        .andExpect(model().attributeDoesNotExist("error"))
        .andExpect(content().string(containsString("id=\"rules-host\"")));
  }

  /**
   * The rendered editor offers all twelve event types and every selector kind, with the enum code
   * as the option value — the event-derived kinds included, so a seeded rule carrying one can be
   * opened and saved.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void pageRendersEveryEnumCodeAsAnOptionValue() throws Exception {
    stubRules();

    mockMvc
        .perform(get("/admin/notification-rules"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"ACCOUNT_DELETION_REQUEST_RESOLVED\"")))
        .andExpect(
            content().string(containsString("value=\"BANK_BOOKING_REQUEST_RESPONSIBLE_REJECTED\"")))
        .andExpect(content().string(containsString("value=\"ACCOUNT_RESPONSIBLE\"")))
        .andExpect(content().string(containsString("value=\"EVENT_RECIPIENT\"")))
        .andExpect(content().string(containsString("value=\"ACCOUNT_GRANT\"")))
        .andExpect(content().string(containsString("data-field=\"fromEvent\"")));
  }

  /**
   * {@code fragment=rules} renders only the table: the rows are there, the page around them — the
   * swap host itself, the form, the selector template — is not, so the swap cannot nest a page
   * inside its own container.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rulesFragmentRendersOnlyTheTable() throws Exception {
    stubRules();

    mockMvc
        .perform(get("/admin/notification-rules").param("fragment", "rules"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/notification-rules :: rules"))
        .andExpect(content().string(containsString("id=\"rules-table\"")))
        .andExpect(content().string(containsString("seeded bank rule")))
        .andExpect(content().string(not(containsString("id=\"rules-host\""))))
        .andExpect(content().string(not(containsString("id=\"rule-form\""))))
        .andExpect(content().string(not(containsString("id=\"selector-row-template\""))));
  }

  /**
   * A code the bundles have no label for — a backend enum value newer than this page — shows as its
   * raw code in the table rather than as an unresolved {@code ??key??}.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void anUnknownCodeRendersAsItselfInTheTable() throws Exception {
    stubRules();

    mockMvc
        .perform(get("/admin/notification-rules").param("fragment", "rules"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(">SOME_FUTURE_EVENT<")))
        .andExpect(content().string(containsString(">SOME_FUTURE_TYPE<")))
        .andExpect(content().string(not(containsString("??admin.notificationRules"))));
  }

  /** Any other fragment value renders the whole page, so a stray parameter cannot blank it. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void anUnknownFragmentValueRendersTheWholePage() throws Exception {
    stubRules();

    mockMvc
        .perform(get("/admin/notification-rules").param("fragment", "something-else"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/notification-rules"));
  }

  /**
   * A backend outage still answers the swap with the table fragment, carrying the load-failure
   * message in place of the rows, rather than an error page painted into the container.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void aBackendOutageRendersTheFailureInsideTheFragment() throws Exception {
    when(backendApiClient.get(contains("/api/v1/notification-rules"), anyTypeRef()))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/admin/notification-rules").param("fragment", "rules"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/notification-rules :: rules"))
        .andExpect(model().attribute("error", "admin.notificationRules.error.load"))
        .andExpect(model().attribute("rules", List.of()))
        .andExpect(content().string(containsString("class=\"alert alert-danger\"")))
        .andExpect(content().string(not(containsString("data-testid=\"empty-state\""))));
  }

  /**
   * The page renders on the list pattern: page head with the admin group as eyebrow and the rule
   * count, the rules as a stacked data table with the enabled flag as a chip instead of a glyph,
   * and the editor as a card rather than a HUD box.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void pageRendersTheListPattern() throws Exception {
    stubRules();

    String html =
        mockMvc
            .perform(get("/admin/notification-rules").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>System &amp; Daten<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>2<")
        .contains("data-list-count-for=\"rules-host\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main.split("btn--cta", -1)).hasSize(2);
    assertThat(main)
        .doesNotContain("colspan")
        .contains("id=\"rules-host\" class=\"card card--flush\"")
        .contains("class=\"data-table data-table--stack\"")
        .contains("data-list-total=\"2\"")
        .containsPattern("class=\"cell-title\">Buchungsantrag gestellt<")
        .contains("class=\"cell-sub\">seeded bank rule<")
        .contains("class=\"chip chip--success\"")
        .contains("class=\"chip chip--muted\"")
        .doesNotContain(">\u2713<");
  }

  /**
   * The rule editor renders on the form pattern inside its card: three numbered sections (event,
   * recipients, options), the selector rows under one column header with an icon remove button and
   * a full-width add button, the two flags as switches, and the actions in a sticky bar with the
   * one primary button; every id the editor script binds stays in place.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void theRuleEditorRendersTheFormPattern() throws Exception {
    stubRules();

    String html =
        mockMvc
            .perform(get("/admin/notification-rules").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String editor =
        html.substring(
            html.indexOf("<section class=\"card admin-rule-editor\""), html.indexOf("</main>"));
    assertThat(editor.split("class=\"form-section\"", -1)).hasSize(4);
    assertThat(editor.split("btn--cta", -1)).hasSize(2);
    assertThat(editor)
        .contains("<form id=\"rule-form\" class=\"form-layout\" data-rule-id=\"\">")
        .contains("1 \u00b7 Ereignis</legend>")
        .contains("2 \u00b7 Empf\u00e4nger</legend>")
        .contains("3 \u00b7 Optionen</legend>")
        .contains("id=\"rule-version\"")
        .contains("id=\"rule-eventType\"")
        .contains("id=\"rule-notificationType\"")
        .contains("id=\"rule-description\"")
        .containsPattern("<label class=\"switch\" for=\"rule-enabled\">")
        .containsPattern("<label class=\"switch\" for=\"rule-excludeActor\">")
        .contains("class=\"selector-row selector-row--head\" aria-hidden=\"true\"")
        .contains(">Auswahl<")
        .contains("id=\"selectors-container\"")
        .contains("class=\"btn btn-ghost selector-add\" id=\"add-selector\"")
        .containsPattern(
            "class=\"form-actions--sticky\">\\s*<button type=\"button\" class=\"btn btn-ghost\""
                + " id=\"rule-cancel\"")
        .contains("Regel speichern")
        .contains("id=\"selector-row-template\"")
        .contains("data-selector-row")
        .containsPattern("<select data-selector-kind aria-label=\"Art\">")
        .contains("data-krt-combobox=\"remote-users\" aria-label=\"Benutzer\">")
        .containsPattern(
            "class=\"btn btn-quiet-danger btn-icon selector-row__remove\" data-selector-remove")
        .doesNotContain("<h3")
        .doesNotContain("krtm-");
  }

  /** No rule yet: the fragment carries the empty state instead of a table. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void anEmptyRuleListRendersTheEmptyState() throws Exception {
    when(backendApiClient.get(contains("/api/v1/notification-rules"), anyTypeRef()))
        .thenReturn(List.of());

    mockMvc
        .perform(get("/admin/notification-rules").param("fragment", "rules").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-testid=\"empty-state\"")))
        .andExpect(content().string(containsString("Noch keine Regeln vorhanden.")))
        .andExpect(content().string(not(containsString("id=\"rules-table\""))));
  }

  /** The rule editor is admin-only. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void refusesANonAdmin() throws Exception {
    mockMvc.perform(get("/admin/notification-rules")).andExpect(status().isForbidden());
  }
}
