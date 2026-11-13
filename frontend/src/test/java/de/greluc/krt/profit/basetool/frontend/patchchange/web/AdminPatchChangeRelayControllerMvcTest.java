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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.CorrectPatchChangeLabelRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeOptions;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePreviewDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePreviewRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchCount;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchCountDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchKind;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.TriggerPatchChangeRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeGroup;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * MVC test of {@link AdminPatchChangeRelayController} (REQ-PATCH-005, REQ-PATCH-008): the form
 * defaults, the preview, the trigger and the label correction reach the backend as typed requests,
 * and a backend refusal comes back as a problem with the backend's status and localised detail.
 */
@SpringBootTest
class AdminPatchChangeRelayControllerMvcTest {

  private static final String ADMIN = "/api/v1/patch-changes/admin";
  private static final String PAGE = "/admin/settings/patch-change";
  private static final UUID ID = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000000a4");
  private static final Instant LIVE_AT = Instant.parse("2026-11-14T20:00:00Z");

  private static final PatchChangeOptions REBUILD =
      new PatchChangeOptions(Set.of(WipeGroup.CONSUMABLES), true, false, true);

  private static final String OPTIONS_JSON =
      "{\"wipeGroups\":[\"CONSUMABLES\"],\"cancelRefineryOrders\":true,"
          + "\"currencyWipe\":false,\"homeLocationPrompt\":true}";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * A backend refusal as the backend client raises it.
   *
   * @param status the HTTP status
   * @param code the problem code
   * @param detail the backend's localised detail
   * @return the failure
   */
  private static BackendServiceException refusal(int status, String code, String detail) {
    return new BackendServiceException("refused", null, status, code, null, List.of(), detail);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theFormDefaultsAreRelayed() throws Exception {
    when(backendApiClient.get(ADMIN + "/form", PatchChangeFormDto.class))
        .thenReturn(
            new PatchChangeFormDto(
                "4.5",
                "4.4",
                LIVE_AT,
                List.of(new PatchChangeFormDto.Preset(PatchKind.REBUILD, REBUILD))));

    mockMvc
        .perform(get(PAGE + "/form").header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.suggestedLabel").value("4.5"))
        .andExpect(jsonPath("$.previousLabel").value("4.4"))
        .andExpect(jsonPath("$.presets[0].kind").value("REBUILD"))
        .andExpect(jsonPath("$.presets[0].options.wipeGroups[0]").value("CONSUMABLES"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePreviewIsRelayedWithTheLiveTimeAndTheOptions() throws Exception {
    PatchChangePreviewRequest request = new PatchChangePreviewRequest(LIVE_AT, REBUILD);
    when(backendApiClient.post(ADMIN + "/preview", request, PatchChangePreviewDto.class))
        .thenReturn(
            new PatchChangePreviewDto(
                LIVE_AT, List.of(new PatchCountDto(PatchCount.REFINERY_ORDERS_CANCELED, 3L))));

    mockMvc
        .perform(
            post(PAGE + "/preview")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"liveAt\":\"2026-11-14T20:00:00Z\",\"options\":" + OPTIONS_JSON + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.counts[0].kind").value("REFINERY_ORDERS_CANCELED"))
        .andExpect(jsonPath("$.counts[0].value").value(3));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aRefusedPreviewComesBackWithTheBackendsDetail() throws Exception {
    when(backendApiClient.post(eq(ADMIN + "/preview"), any(), eq(PatchChangePreviewDto.class)))
        .thenThrow(refusal(400, "BAD_REQUEST", "Die Live-Zeit liegt in der Zukunft."));

    mockMvc
        .perform(
            post(PAGE + "/preview")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"liveAt\":\"2099-01-01T00:00:00Z\",\"options\":" + OPTIONS_JSON + "}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Die Live-Zeit liegt in der Zukunft."));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theTriggerAnswersCreatedWithTheRecordedPatchChange() throws Exception {
    TriggerPatchChangeRequest request =
        new TriggerPatchChangeRequest("4.5", PatchKind.REBUILD, LIVE_AT, REBUILD, "4.5");
    when(backendApiClient.post(ADMIN, request, PatchChangeDto.class))
        .thenReturn(
            new PatchChangeDto(
                ID, 0L, "4.5", PatchKind.REBUILD, LIVE_AT, LIVE_AT, "Admin", REBUILD, List.of()));

    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"label\":\"4.5\",\"kind\":\"REBUILD\",\"liveAt\":\"2026-11-14T20:00:00Z\","
                        + "\"options\":"
                        + OPTIONS_JSON
                        + ",\"confirmation\":\"4.5\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(ID.toString()))
        .andExpect(jsonPath("$.label").value("4.5"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aWrongConfirmationComesBackAsTheBackendsRefusal() throws Exception {
    when(backendApiClient.post(eq(ADMIN), any(), eq(PatchChangeDto.class)))
        .thenThrow(
            refusal(
                400, "BAD_REQUEST", "Die Bestätigung stimmt nicht mit der Bezeichnung überein."));

    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"label\":\"4.5\",\"kind\":\"REBUILD\",\"options\":"
                        + OPTIONS_JSON
                        + ",\"confirmation\":\"4.4\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail")
                .value("Die Bestätigung stimmt nicht mit der Bezeichnung überein."));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aLabelCorrectionCarriesTheVersionToTheBackend() throws Exception {
    CorrectPatchChangeLabelRequest request = new CorrectPatchChangeLabelRequest("4.4.1", 2L);
    when(backendApiClient.patch(ADMIN + "/{id}/label", request, PatchChangeDto.class, ID))
        .thenReturn(
            new PatchChangeDto(
                ID, 3L, "4.4.1", PatchKind.REBUILD, LIVE_AT, LIVE_AT, "Admin", REBUILD, List.of()));

    mockMvc
        .perform(
            patch(PAGE + "/" + ID + "/label")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"4.4.1\",\"version\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.label").value("4.4.1"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aStaleLabelCorrectionComesBackAsAConflict() throws Exception {
    when(backendApiClient.patch(eq(ADMIN + "/{id}/label"), any(), eq(PatchChangeDto.class), eq(ID)))
        .thenThrow(refusal(409, "OPTIMISTIC_LOCK", "Die Daten wurden zwischenzeitlich geändert."));

    mockMvc
        .perform(
            patch(PAGE + "/" + ID + "/label")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"4.4.1\",\"version\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMemberCannotTriggerAPatchChange() throws Exception {
    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"label\":\"4.5\",\"kind\":\"REBUILD\",\"options\":"
                        + OPTIONS_JSON
                        + ",\"confirmation\":\"4.5\"}"))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never()).post(anyString(), any(), eq(PatchChangeDto.class));
  }
}
