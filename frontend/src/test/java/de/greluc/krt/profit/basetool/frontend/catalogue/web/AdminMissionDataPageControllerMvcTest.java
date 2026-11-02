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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.JobTypeDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC render test for {@link AdminMissionDataPageController}: the full page renders all three swap
 * targets, and each {@code ?fragment=<section>-results} request renders only that section's table
 * (REQ-FE-002).
 */
@SpringBootTest
class AdminMissionDataPageControllerMvcTest {

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
   * Stubs the two backend catalogue calls (job-types / frequency-types) with one row each, so every
   * section table renders.
   */
  private void stubBoth() {
    PageResponse<JobTypeDto> jobTypes =
        new PageResponse<>(
            List.of(
                new JobTypeDto(
                    UUID.randomUUID(), "Frag JT", "d", "MISSION", null, true, false, null, 0L)),
            0,
            1000,
            1L,
            1,
            List.of());
    PageResponse<FrequencyTypeDto> freqTypes =
        new PageResponse<>(
            List.of(new FrequencyTypeDto(UUID.randomUUID(), "Frag FT", "d", true, null, 0L)),
            0,
            1000,
            1L,
            1,
            List.of());
    when(backendApiClient.get(contains("/api/v1/job-types"), anyTypeRef(), any(Object[].class)))
        .thenReturn(jobTypes);
    when(backendApiClient.get(
            contains("/api/v1/frequency-types"), anyTypeRef(), any(Object[].class)))
        .thenReturn(freqTypes);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void reorderFrequencyTypes_keepsTheBackendLockConflict() throws Exception {
    when(backendApiClient.post(eq("/api/v1/frequency-types/reorder"), any(), eq(Void.class)))
        .thenThrow(
            new BackendServiceException(
                "locked", null, 409, "PESSIMISTIC_LOCK", null, List.of(), null));

    mockMvc
        .perform(
            post("/admin/mission-data/frequency-types/reorder")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[\"" + UUID.randomUUID() + "\"]"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PESSIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void reorderFrequencyTypes_sendsALostSessionToTheLogin() throws Exception {
    when(backendApiClient.post(eq("/api/v1/frequency-types/reorder"), any(), eq(Void.class)))
        .thenThrow(new ReauthenticationRequiredException("token gone", null));

    mockMvc
        .perform(
            post("/admin/mission-data/frequency-types/reorder")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[\"" + UUID.randomUUID() + "\"]"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Reauthenticate"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fullPage_rendersBothSwapWrappers() throws Exception {
    stubBoth();

    mockMvc
        .perform(get("/admin/mission-data"))
        .andExpect(status().isOk())
        .andExpect(view().name("catalogue/admin/mission-data"))
        .andExpect(content().string(containsString("id=\"jobtypes-results\"")))
        .andExpect(content().string(containsString("id=\"freqtypes-results\"")));
  }

  /**
   * The page follows the master-detail pattern (REQ-UI-027): page head with the admin eyebrow and
   * no primary action, the three data kinds as master rows with counts, and one detail pane per
   * kind with its switch, its create button as the pane's primary action and its stacked table.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fullPage_rendersThePatternBasics() throws Exception {
    stubBoth();

    String html =
        mockMvc
            .perform(get("/admin/mission-data").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String main = html.substring(html.indexOf("<main"), html.indexOf("id=\"jobtype-modal\""));

    assertThat(html)
        .containsPattern("class=\"page-eyebrow\"[^>]*>Stammdaten<")
        .doesNotContain("class=\"page-actions\"")
        .contains("id=\"add-jobtype-btn\" class=\"btn btn--cta\"")
        .contains("id=\"add-freqtype-btn\" class=\"btn btn--cta\"")
        .contains("class=\"krt-switch\" for=\"includeInactiveJobTypes\"")
        .contains("class=\"krt-switch\" for=\"includeInactiveFrequencyTypes\"")
        .contains(">Einsatz<")
        .contains(">Nein<")
        .doesNotContain(">MISSION<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("md-box__toggle");
    assertThat(main)
        .contains("class=\"master-detail md-md\"")
        .containsPattern(
            "class=\"master-row md-kind-row is-active\" role=\"tab\" id=\"md-kind-jobtypes\"")
        .containsPattern("data-list-count-for=\"jobtypes-results\"\\s*>1<")
        .containsPattern("id=\"jobtypes-box\"[^>]*role=\"tabpanel\"[^>]*>")
        .containsPattern("id=\"freqtypes-box\"[^>]*hidden")
        .doesNotContainPattern("id=\"jobtypes-box\"[^>]*hidden")
        .contains("data-list-total=\"1\"")
        .doesNotContain("krtm-");
    assertThat(main.split("btn--cta", -1)).hasSize(3);
    assertThat(main.split("data-table data-table--stack", -1)).hasSize(3);
  }

  /** The {@code kind} parameter opens that data kind's pane, an unknown value the first. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_kindParameter_selectsThePane() throws Exception {
    stubBoth();

    String freq =
        mockMvc
            .perform(get("/admin/mission-data").param("kind", "freqtypes"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknown =
        mockMvc
            .perform(get("/admin/mission-data").param("kind", "nonsense"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(freq)
        .contains(
            "class=\"master-row md-kind-row is-active\" role=\"tab\" id=\"md-kind-freqtypes\"")
        .containsPattern("id=\"jobtypes-box\"[^>]*hidden")
        .doesNotContainPattern("id=\"freqtypes-box\"[^>]*hidden");
    assertThat(unknown)
        .contains(
            "class=\"master-row md-kind-row is-active\" role=\"tab\" id=\"md-kind-jobtypes\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void list_ShouldExcludeCheckboxesFromFormGroupInputRule() throws Exception {
    stubBoth();

    mockMvc
        .perform(get("/admin/mission-data"))
        .andExpect(status().isOk())
        .andExpect(
            PageStylesheets.content(
                containsString(
                    ".form-group input:where(:not([type='checkbox'], [type='radio']))")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fragmentJobtypes_rendersOnlyJobTable() throws Exception {
    stubBoth();

    mockMvc
        .perform(get("/admin/mission-data").param("fragment", "jobtypes-results"))
        .andExpect(status().isOk())
        .andExpect(view().name("catalogue/admin/mission-data :: jobtypes-results"))
        .andExpect(content().string(containsString("Frag JT")))
        .andExpect(content().string(not(containsString("id=\"jobtypes-results\""))))
        .andExpect(content().string(not(containsString("id=\"jobtype-modal\""))))
        .andExpect(content().string(not(containsString("id=\"add-jobtype-btn\""))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fragmentFreqtypes_rendersOnlyFreqTable() throws Exception {
    stubBoth();

    mockMvc
        .perform(get("/admin/mission-data").param("fragment", "freqtypes-results"))
        .andExpect(status().isOk())
        .andExpect(view().name("catalogue/admin/mission-data :: freqtypes-results"))
        .andExpect(content().string(containsString("Frag FT")))
        .andExpect(content().string(containsString("class=\"draggable-row")))
        .andExpect(content().string(not(containsString("id=\"freqtypes-results\""))))
        .andExpect(content().string(not(containsString("id=\"frequency-type-modal\""))))
        .andExpect(content().string(not(containsString("id=\"add-freqtype-btn\""))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteJobTypeAjax_withHeader_returns200() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(eq("/api/v1/job-types/{id}"), eq(Void.class), eq(id)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/admin/mission-data/job-types/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());
  }
}
