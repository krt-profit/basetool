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

package de.greluc.krt.profit.basetool.frontend.settings.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.settings.model.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * MVC test for {@link AdminSettingsPageController}: the settings AJAX twin applies the invariants
 * and per-setting PUTs and returns the new versions as JSON, a yellow &gt;= red violation returns
 * {@code 422 problem+json}, without {@code X-Requested-With} the URL still redirects, and the page
 * renders on the form pattern with one profit toggle per active Spezialkommando.
 */
@SpringBootTest
class AdminSettingsPageControllerMvcTest {

  private static final Pattern SK_TOGGLE = Pattern.compile("<input[^>]*sk-profit-toggle[^>]*>");

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

  /**
   * The page stylesheet styles no form input itself: the form layout sizes the fields, so no page
   * rule can stretch the per-unit switches the way an unscoped input rule once did.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void viewSettings_pageStylesheetStylesNoInputThatCouldReachTheSwitches() throws Exception {
    mockMvc
        .perform(get("/admin/settings"))
        .andExpect(status().isOk())
        .andExpect(PageStylesheets.content(not(containsString(".form-group input"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void viewSettings_rendersOneProfitTogglePerActiveSpecialCommandSortedByName() throws Exception {
    UUID zuluId = UUID.randomUUID();
    UUID alphaId = UUID.randomUUID();
    SpecialCommandDto zulu =
        new SpecialCommandDto(zuluId, "Zulu-Kommando", "ZK", "", true, true, 3L);
    SpecialCommandDto alpha =
        new SpecialCommandDto(alphaId, "Alpha-Kommando", "AK", "", true, false, 1L);
    when(backendApiClient.get(
            eq("/api/v1/special-commands?size=1000&sort=name,asc&page={page}"),
            anyTypeRef(),
            eq(0)))
        .thenReturn(new PageResponse<>(List.of(zulu, alpha), 0, 1000, 2, 1, List.of()));

    String html =
        mockMvc
            .perform(get("/admin/settings"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Matcher toggles = SK_TOGGLE.matcher(html);
    assertThat(toggles.find()).as("first SK toggle").isTrue();
    String first = toggles.group();
    assertThat(toggles.find()).as("second SK toggle").isTrue();
    String second = toggles.group();
    assertThat(toggles.find()).as("exactly one toggle per SK").isFalse();
    assertThat(first).contains("data-sk-id=\"" + alphaId + "\"").doesNotContain("checked");
    assertThat(second).contains("data-sk-id=\"" + zuluId + "\"").contains("checked");
    assertThat(html).contains("Alpha-Kommando", "Zulu-Kommando");
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

  /**
   * Renders the settings page in German with the refinery rounding stored as {@code DOWN} and one
   * squadron in the picker catalogue.
   *
   * @param squadrons the squadrons the catalogue answers with
   * @return the rendered page
   * @throws Exception if the request fails
   */
  private String renderPage(List<SquadronDto> squadrons) throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/settings/refinery.rounding.mode"), eq(SystemSettingDto.class)))
        .thenReturn(new SystemSettingDto("refinery.rounding.mode", "DOWN", 4L));
    when(backendApiClient.get(
            eq("/api/v1/squadrons?size=1000&sort=name,asc&page={page}"), anyTypeRef(), eq(0)))
        .thenReturn(new PageResponse<>(squadrons, 0, 1000, squadrons.size(), 1, List.of()));
    return mockMvc
        .perform(get("/admin/settings").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The page renders on the form pattern: a page head under the "System &amp; Daten" eyebrow, the
   * three saved settings as numbered sections of one form card with a single sticky primary action,
   * the rounding mode as a segment carrying the stored value, and the per-unit flags as switches in
   * their own numbered sections that save on change.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void thePageRendersTheFormPattern() throws Exception {
    SquadronDto iridium =
        new SquadronDto(UUID.randomUUID(), "IRIDIUM", "IRI", "", true, true, false, 0L);

    String html = renderPage(List.of(iridium));

    assertThat(html)
        .contains("data-testid=\"page-head\"")
        .containsPattern("<span class=\"page-eyebrow\"[^>]*>System &amp; Daten<")
        .contains("<h1>Systemeinstellungen</h1>")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main.split("btn--cta", -1)).hasSize(2);
    assertThat(main.split("class=\"form-section\"", -1)).hasSize(7);
    assertThat(main)
        .doesNotContain("krtm-")
        .doesNotContain("krt-table")
        .containsPattern("<form id=\"admin-settings-form\" class=\"form-layout card\"")
        .contains("1 \u00b7 Auftragsverwaltung</legend>")
        .contains("2 \u00b7 Raffinerie</legend>")
        .contains("3 \u00b7 Operationen</legend>")
        .containsPattern(
            "class=\"form-actions--sticky\">\\s*<button type=\"submit\" class=\"btn btn--cta\"")
        .contains("Einstellungen speichern")
        .doesNotContain("<select id=\"refineryRoundingMode\"")
        .contains("class=\"segmented segmented--block segmented--lg\"")
        .containsPattern("name=\"refineryRoundingMode\" value=\"DOWN\" checked")
        .doesNotContain("value=\"UP\" checked")
        .contains("4 \u00b7 Bef\u00f6rderungssystem pro Staffel</span>")
        .contains("\u00b7 speichert sofort")
        .contains("class=\"data-table data-table--stack\"")
        .contains("<label class=\"switch\">")
        .containsPattern("class=\"squadron-promotion-toggle\"[^>]*checked")
        .containsPattern(
            "class=\"squadron-profit-toggle\"[^>]*aria-label=\"Auftragsbearbeitung: IRI\"")
        .contains("class=\"cell-title\">IRI<");
  }

  /** Without squadrons the per-squadron sections show the empty state instead of a table. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void noSquadronsRenderTheEmptyState() throws Exception {
    String html = renderPage(List.of());

    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Staffeln gefunden.")
        .contains("Sobald es Staffeln gibt, erscheinen sie hier.")
        .doesNotContain("squadron-promotion-toggle")
        .doesNotContain("squadron-profit-toggle");
  }
}
