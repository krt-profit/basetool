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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierWriteDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test for {@link AdminQualityTiersPageController} and {@link
 * AdminQualityTiersRelayController}: the page and its table fragment render for an administrator
 * only, and the create, update and delete relays forward to {@code /api/v1/admin/quality-tiers},
 * evict the quality-tier catalogue and relay a backend {@code 409}.
 */
@SpringBootTest
class AdminQualityTiersPageControllerMvcTest {

  private static final String BACKEND_BASE = "/api/v1/admin/quality-tiers";

  private static final String PAGE = "/admin/quality-tiers";

  private static final UUID BASE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a0");

  private static final UUID GOOD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Builds the catalogue the backend answers with: the base tier and one used tier.
   *
   * @return the base tier with floor 0 and an active {@code GOOD} tier with floor 650
   */
  private static List<QualityTierDto> catalogue() {
    return List.of(
        new QualityTierDto(BASE_ID, "NONE", 0, "Keine", "None", 0, true, 0L),
        new QualityTierDto(GOOD_ID, "GOOD", 650, "Gut", "Good", 10, true, 3L));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void anAdminSeesTheTierTable() throws Exception {
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef())).thenReturn(catalogue());

    mockMvc
        .perform(get(PAGE))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"qt-table\"")))
        .andExpect(content().string(containsString("data-tier-id=\"" + GOOD_ID + "\"")))
        .andExpect(content().string(containsString("id=\"qt-modal\"")))
        .andExpect(content().string(containsString("id=\"qt-delete-modal\"")))
        .andExpect(content().string(containsString("/js/admin-quality-tiers.js")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theBaseTierOffersNoDelete() throws Exception {
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef())).thenReturn(catalogue());

    String html =
        mockMvc
            .perform(get(PAGE).param("fragment", "results"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("<main"))))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("data-qt-delete").contains("data-id=\"" + GOOD_ID + "\"");
    assertThat(html.split("data-qt-delete", -1)).hasSize(2);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aBackendFailureShowsTheLoadError() throws Exception {
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef()))
        .thenThrow(new BackendServiceException("down", null, 503));

    mockMvc
        .perform(get(PAGE))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("alert-danger")));
  }

  /**
   * The page renders on the list pattern: the title from the bundle under the "Stammdaten" eyebrow,
   * the create button as the only primary action in the page head, the tiers in a stacked data
   * table whose total feeds the count chip, the in-use hint as an alert, no HUD box.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void thePageRendersTheListPattern() throws Exception {
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef())).thenReturn(catalogue());

    String html =
        mockMvc
            .perform(get(PAGE).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .containsPattern("<h1>Qualitätsstufen</h1>")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Stammdaten<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>2<")
        .contains("data-list-count-for=\"qt-results\"")
        .doesNotContain("??admin.qualityTiers")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main.split("btn--cta", -1)).hasSize(2);
    assertThat(main)
        .containsPattern("class=\"page-actions\">\\s*<button[^>]*id=\"qt-add-btn\"")
        .doesNotContain("colspan")
        .contains("class=\"alert alert-info\"")
        .contains("id=\"qt-results\" class=\"card card--flush\"")
        .contains("class=\"data-table data-table--stack\"")
        .contains("data-list-total=\"2\"")
        .containsPattern("class=\"cell-title data-value\">GOOD<")
        .contains("class=\"chip chip--success\">aktiv<");
  }

  /**
   * The create/edit dialog lays its six fields out on the form grid, offers the status as an
   * active/inactive segment posting the same {@code active} name with active preselected, and keeps
   * the ids the page script binds.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void theTierDialogRendersTheFormGrid() throws Exception {
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef())).thenReturn(catalogue());

    String html =
        mockMvc
            .perform(get(PAGE).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String dialog =
        html.substring(html.indexOf("id=\"qt-modal\""), html.indexOf("id=\"qt-delete-modal\""));
    assertThat(dialog)
        .contains("krt-modal--wide")
        .contains("<form id=\"qt-form\" class=\"qt-form\"")
        .contains("<div class=\"form-grid\">")
        .doesNotContain("qt-form-grid")
        .doesNotContain("type=\"checkbox\"")
        .contains("id=\"qt-code\"")
        .contains("id=\"qt-min-quality\"")
        .contains("id=\"qt-label-de\"")
        .contains("id=\"qt-label-en\"")
        .contains("id=\"qt-sort-order\"")
        .contains("id=\"qt-base-hint\"")
        .contains("class=\"segmented segmented--block segmented--lg\"")
        .contains("data-testid=\"segment-active-true\"")
        .contains("data-testid=\"segment-active-false\"")
        .containsPattern("name=\"active\" value=\"true\" checked")
        .doesNotContain("value=\"false\" checked")
        .contains(">aktiv<")
        .contains(">inaktiv<")
        .contains("Stufe speichern");
    assertThat(dialog.split("btn--cta", -1)).hasSize(2);
  }

  /** An empty catalogue renders the empty state in the results fragment, without a table. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void anEmptyCatalogueRendersTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef())).thenReturn(List.of());

    mockMvc
        .perform(get(PAGE).param("fragment", "results").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-testid=\"empty-state\"")))
        .andExpect(content().string(containsString("Noch keine Qualitätsstufen vorhanden.")))
        .andExpect(content().string(not(containsString("id=\"qt-table\""))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMemberIsRefused() throws Exception {
    mockMvc.perform(get(PAGE)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"FINE\"}"))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never()).post(any(), any(), eq(QualityTierDto.class));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createRelaysToTheBackendAndEvictsTheCatalogue() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.post(eq(BACKEND_BASE), any(), eq(QualityTierDto.class)))
        .thenReturn(new QualityTierDto(id, "FINE", 800, "Fein", "Fine", 20, true, 0L));

    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"code\":\" fine \",\"minQuality\":800,\"labelDe\":\" Fein \","
                        + "\"labelEn\":\"Fine\",\"sortOrder\":20,\"active\":true,\"version\":7}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.code").value("FINE"));

    ArgumentCaptor<QualityTierWriteDto> body = ArgumentCaptor.forClass(QualityTierWriteDto.class);
    verify(backendApiClient).post(eq(BACKEND_BASE), body.capture(), eq(QualityTierDto.class));
    assertThat(body.getValue())
        .isEqualTo(new QualityTierWriteDto("FINE", 800, "Fein", "Fine", 20, true, null));
    verify(backendApiClient).evict(CacheDomain.QUALITY_TIER);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void updateRelaysTheVersionAndEvictsTheCatalogue() throws Exception {
    when(backendApiClient.put(
            eq("/api/v1/admin/quality-tiers/{id}"), any(), eq(QualityTierDto.class), eq(GOOD_ID)))
        .thenReturn(new QualityTierDto(GOOD_ID, "GOOD", 650, "Gut", "Good", 10, false, 4L));

    mockMvc
        .perform(
            post(PAGE + "/" + GOOD_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"code\":\"GOOD\",\"minQuality\":650,\"labelDe\":\"Gut\","
                        + "\"labelEn\":\"Good\",\"sortOrder\":10,\"active\":false,\"version\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4));

    ArgumentCaptor<QualityTierWriteDto> body = ArgumentCaptor.forClass(QualityTierWriteDto.class);
    verify(backendApiClient)
        .put(
            eq("/api/v1/admin/quality-tiers/{id}"),
            body.capture(),
            eq(QualityTierDto.class),
            eq(GOOD_ID));
    assertThat(body.getValue().version()).isEqualTo(3L);
    assertThat(body.getValue().active()).isFalse();
    verify(backendApiClient).evict(CacheDomain.QUALITY_TIER);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteRelaysToTheBackendAndEvictsTheCatalogue() throws Exception {
    when(backendApiClient.delete("/api/v1/admin/quality-tiers/{id}", Void.class, GOOD_ID))
        .thenReturn(null);

    mockMvc
        .perform(
            post(PAGE + "/" + GOOD_ID + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());

    verify(backendApiClient).delete("/api/v1/admin/quality-tiers/{id}", Void.class, GOOD_ID);
    verify(backendApiClient).evict(CacheDomain.QUALITY_TIER);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aTierInUseIsRelayedAs409WithoutEviction() throws Exception {
    when(backendApiClient.delete("/api/v1/admin/quality-tiers/{id}", Void.class, GOOD_ID))
        .thenThrow(
            new BackendServiceException(
                "in use", null, 409, "ENTITY_IN_USE", null, List.of(), "in use"));

    mockMvc
        .perform(
            post(PAGE + "/" + GOOD_ID + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ENTITY_IN_USE"));

    verify(backendApiClient, never()).evict(any(CacheDomain[].class));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aStaleVersionIsRelayedAs409() throws Exception {
    when(backendApiClient.put(
            eq("/api/v1/admin/quality-tiers/{id}"), any(), eq(QualityTierDto.class), eq(GOOD_ID)))
        .thenThrow(
            new BackendServiceException(
                "stale", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "stale"));

    mockMvc
        .perform(
            post(PAGE + "/" + GOOD_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"code\":\"GOOD\",\"minQuality\":650,\"labelDe\":\"Gut\","
                        + "\"labelEn\":\"Good\",\"sortOrder\":10,\"active\":true,\"version\":2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }
}
