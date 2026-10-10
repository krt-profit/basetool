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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionForm;
import de.greluc.krt.profit.basetool.frontend.mission.model.ParticipantForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncLocalBus;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

class MissionPageControllerTest {

  private static final ParallelPageLoader PARALLEL = new ParallelPageLoader();

  /**
   * Builds a {@link FrontendAuthHelperService} mock for injection into the {@link
   * MissionWriteController} under test.
   *
   * @param anonymous the anonymity flag the test case models
   * @return an auth-helper mock for the controller under test
   */
  private static FrontendAuthHelperService authHelper(boolean unusedAnonymousFlag) {
    return mock(FrontendAuthHelperService.class);
  }

  @Test
  void createMissionForm_ShouldInitializeModelCorrectly() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionPageController controller =
        new MissionPageController(
            new MissionBackendClient(backendApiClient),
            mock(FrontendAuthHelperService.class),
            PARALLEL);
    Model model = new ConcurrentModel();

    String viewName = controller.createMissionForm(model, null, null);

    assertEquals("mission-detail", viewName);
    assertTrue(model.containsAttribute("isNew"));
    assertTrue((Boolean) model.getAttribute("isNew"));

    assertTrue(model.containsAttribute("missionForm"));
    MissionForm missionForm = (MissionForm) model.getAttribute("missionForm");
    assertNotNull(missionForm);

    assertEquals("", missionForm.name());
    assertEquals("", missionForm.description());
    assertEquals("PLANNED", missionForm.status());
    assertEquals("", missionForm.meetingTime());
    assertEquals("", missionForm.plannedStartTime());
    assertEquals("", missionForm.plannedEndTime());
  }

  @Test
  void addFormsToModel_fragmentRefetch_skipsUsersMeParticipantPrefill() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionPageController controller =
        new MissionPageController(
            new MissionBackendClient(backendApiClient),
            mock(FrontendAuthHelperService.class),
            PARALLEL);
    Model model = new ConcurrentModel();
    OidcUser principal = mock(OidcUser.class);

    controller.addFormsToModel(model, principal, false);

    verify(backendApiClient, never()).get(eq("/api/v1/users/me"), eq(UserDto.class));
    assertTrue(model.containsAttribute("participantForm"));
  }

  @Test
  void addFormsToModel_fullRender_prefillsUsersMeParticipantForm() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    when(backendApiClient.get(eq("/api/v1/users/me"), eq(UserDto.class))).thenReturn(null);
    MissionPageController controller =
        new MissionPageController(
            new MissionBackendClient(backendApiClient),
            mock(FrontendAuthHelperService.class),
            PARALLEL);
    Model model = new ConcurrentModel();
    OidcUser principal = mock(OidcUser.class);

    controller.addFormsToModel(model, principal, true);

    verify(backendApiClient).get(eq("/api/v1/users/me"), eq(UserDto.class));
    assertTrue(model.containsAttribute("participantForm"));
  }

  @Test
  void addParticipant_ShouldCallWebClient() {
    UUID id = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionWriteController controller =
        new MissionWriteController(
            new MissionBackendClient(backendApiClient),
            mock(MessageSource.class),
            new MissionPageController(
                new MissionBackendClient(backendApiClient),
                mock(FrontendAuthHelperService.class),
                PARALLEL),
            mock(LiveSyncLocalBus.class));

    when(backendApiClient.post(anyString(), any(), eq(Void.class), any(Object[].class)))
        .thenReturn(null);

    String view =
        controller.addParticipant(
            id,
            new ParticipantForm(null, "Guest", null, null, "Comment", null, null, null, null, null),
            mock(BindingResult.class),
            new ConcurrentModel(),
            mock(RedirectAttributes.class),
            null);

    assertEquals("redirect:/missions/" + id, view);
    verify(backendApiClient)
        .post(eq("/api/v1/missions/{id}/participants/add"), any(), eq(Void.class), eq(id));
  }

  @Test
  void addParticipant_AmbiguousName_ShouldExposeLocalizedToast() {
    UUID id = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionWriteController controller =
        new MissionWriteController(
            new MissionBackendClient(backendApiClient),
            mock(MessageSource.class),
            new MissionPageController(
                new MissionBackendClient(backendApiClient),
                mock(FrontendAuthHelperService.class),
                PARALLEL),
            mock(LiveSyncLocalBus.class));
    RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

    when(backendApiClient.post(anyString(), any(), eq(Void.class), any(Object[].class)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "ambiguous", null, 409));

    String view =
        controller.addParticipant(
            id,
            new ParticipantForm(
                null, "Shared Alias", null, null, "Comment", null, null, null, null, null),
            mock(BindingResult.class),
            new ConcurrentModel(),
            redirectAttributes,
            null);

    assertEquals("redirect:/missions/" + id, view);
    verify(redirectAttributes)
        .addFlashAttribute("errorToast", "error.mission.participant.ambiguous");
    verify(redirectAttributes, never())
        .addFlashAttribute("errorToast", "error.mission.participant.add");
  }

  @Test
  void addParticipant_WithUserId_Authenticated_ShouldCallWebClient() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionWriteController controller =
        new MissionWriteController(
            new MissionBackendClient(backendApiClient),
            mock(MessageSource.class),
            new MissionPageController(
                new MissionBackendClient(backendApiClient),
                mock(FrontendAuthHelperService.class),
                PARALLEL),
            mock(LiveSyncLocalBus.class));
    OidcUser user = mock(OidcUser.class);

    when(backendApiClient.post(anyString(), any(), eq(Void.class), any(Object[].class)))
        .thenReturn(null);

    String view =
        controller.addParticipant(
            id,
            new ParticipantForm(userId, null, null, null, "Comment", null, null, null, null, null),
            mock(BindingResult.class),
            new ConcurrentModel(),
            mock(RedirectAttributes.class),
            user);

    assertEquals("redirect:/missions/" + id, view);
    verify(backendApiClient)
        .post(eq("/api/v1/missions/{id}/participants/add"), any(), eq(Void.class), eq(id));
  }

  @Test
  void setPartyLead_ShouldCallBackendPutAndExposeSuccessToast() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionWriteController controller =
        new MissionWriteController(
            new MissionBackendClient(backendApiClient),
            mock(MessageSource.class),
            new MissionPageController(
                new MissionBackendClient(backendApiClient),
                mock(FrontendAuthHelperService.class),
                PARALLEL),
            mock(LiveSyncLocalBus.class));
    RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

    when(backendApiClient.put(anyString(), any(), eq(Void.class), any(Object[].class)))
        .thenReturn(null);

    String view = controller.setPartyLead(id, userId, "Alice", 2L, redirectAttributes);

    assertEquals("redirect:/missions/" + id, view);
    verify(backendApiClient)
        .put(eq("/api/v1/missions/{id}/party-lead"), any(), eq(Void.class), eq(id));
    verify(redirectAttributes).addFlashAttribute("successToast", "notification.success.save");
  }

  @Test
  void setPartyLead_Conflict_ShouldExposeLocalizedToast() {
    UUID id = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionWriteController controller =
        new MissionWriteController(
            new MissionBackendClient(backendApiClient),
            mock(MessageSource.class),
            new MissionPageController(
                new MissionBackendClient(backendApiClient),
                mock(FrontendAuthHelperService.class),
                PARALLEL),
            mock(LiveSyncLocalBus.class));
    RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

    when(backendApiClient.put(anyString(), any(), eq(Void.class), any(Object[].class)))
        .thenThrow(
            new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
                "conflict", null, 409));

    String view = controller.setPartyLead(id, null, "Shared Alias", 0L, redirectAttributes);

    assertEquals("redirect:/missions/" + id, view);
    verify(redirectAttributes).addFlashAttribute("errorToast", "error.mission.party_lead.conflict");
    verify(redirectAttributes, never())
        .addFlashAttribute("errorToast", "error.mission.party_lead.update");
  }

  @Test
  void deleteParticipant_ShouldCallWebClient() {
    UUID id = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionWriteController controller =
        new MissionWriteController(
            new MissionBackendClient(backendApiClient),
            mock(MessageSource.class),
            new MissionPageController(
                new MissionBackendClient(backendApiClient),
                mock(FrontendAuthHelperService.class),
                PARALLEL),
            mock(LiveSyncLocalBus.class));
    OidcUser user = mock(OidcUser.class);

    when(backendApiClient.delete(anyString(), eq(Void.class), any(Object[].class)))
        .thenReturn(null);

    String view =
        controller.deleteParticipant(id, participantId, user, mock(RedirectAttributes.class));

    assertEquals("redirect:/missions/" + id, view);
    verify(backendApiClient)
        .delete(
            eq("/api/v1/missions/{id}/participants/{participantId}/slim"),
            eq(Void.class),
            eq(id),
            eq(participantId));
  }

  @Test
  void deleteParticipant_Anonymous_ShouldCallPublicWebClient() {
    UUID id = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionWriteController controller =
        new MissionWriteController(
            new MissionBackendClient(backendApiClient),
            mock(MessageSource.class),
            new MissionPageController(
                new MissionBackendClient(backendApiClient),
                mock(FrontendAuthHelperService.class),
                PARALLEL),
            mock(LiveSyncLocalBus.class));

    when(backendApiClient.delete(anyString(), eq(Void.class), any(Object[].class)))
        .thenReturn(null);

    String view =
        controller.deleteParticipant(id, participantId, null, mock(RedirectAttributes.class));

    assertEquals("redirect:/missions/" + id, view);
    verify(backendApiClient)
        .delete(
            eq("/api/v1/missions/{id}/participants/{participantId}/slim"),
            eq(Void.class),
            eq(id),
            eq(participantId));
  }

  @Test
  void updateParticipant_ShouldCallWebClient() {
    UUID id = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionWriteController controller =
        new MissionWriteController(
            new MissionBackendClient(backendApiClient),
            mock(MessageSource.class),
            new MissionPageController(
                new MissionBackendClient(backendApiClient),
                mock(FrontendAuthHelperService.class),
                PARALLEL),
            mock(LiveSyncLocalBus.class));
    OidcUser user = mock(OidcUser.class);

    when(backendApiClient.put(anyString(), any(), eq(Void.class), any(Object[].class)))
        .thenReturn(null);

    String view =
        controller.updateParticipant(
            id,
            participantId,
            new ParticipantForm(
                null, null, UUID.randomUUID(), null, "New Comment", null, null, null, null, null),
            mock(BindingResult.class),
            new ConcurrentModel(),
            mock(RedirectAttributes.class),
            user);

    assertEquals("redirect:/missions/" + id, view);
    verify(backendApiClient)
        .put(
            eq("/api/v1/missions/{id}/participants/{participantId}/slim"),
            any(),
            eq(Void.class),
            eq(id),
            eq(participantId));
  }

  @Test
  void updateParticipant_Anonymous_ShouldCallPublicWebClient() {
    UUID id = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionWriteController controller =
        new MissionWriteController(
            new MissionBackendClient(backendApiClient),
            mock(MessageSource.class),
            new MissionPageController(
                new MissionBackendClient(backendApiClient),
                mock(FrontendAuthHelperService.class),
                PARALLEL),
            mock(LiveSyncLocalBus.class));

    when(backendApiClient.put(anyString(), any(), eq(Void.class), any(Object[].class)))
        .thenReturn(null);

    String view =
        controller.updateParticipant(
            id,
            participantId,
            new ParticipantForm(
                null, null, UUID.randomUUID(), null, "New Comment", null, null, null, null, null),
            mock(BindingResult.class),
            new ConcurrentModel(),
            mock(RedirectAttributes.class),
            null);

    assertEquals("redirect:/missions/" + id, view);
    verify(backendApiClient)
        .put(
            eq("/api/v1/missions/{id}/participants/{participantId}/slim"),
            any(),
            eq(Void.class),
            eq(id),
            eq(participantId));
  }

  @Test
  void listMissions_ShowPastTrue_User_ShouldIncludeAllStatuses() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionPageController controller =
        new MissionPageController(
            new MissionBackendClient(backendApiClient),
            mock(FrontendAuthHelperService.class),
            PARALLEL);
    Model model = new ConcurrentModel();
    OidcUser user = mock(OidcUser.class);

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
    when(backendApiClient.get(uriCaptor.capture(), anyTypeRef()))
        .thenReturn(
            new de.greluc.krt.profit.basetool.frontend.model.PageResponse<>(
                Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList()));

    controller.listMissions(null, null, null, null, true, null, null, null, null, model, user);

    String uri = uriCaptor.getValue();
    assertTrue(uri.contains("status=COMPLETED"));
    assertTrue(uri.contains("status=CANCELLED"));
    assertTrue(uri.contains("status=PLANNED"));
    assertTrue(uri.contains("status=ACTIVE"));
    assertTrue((Boolean) model.getAttribute("showPast"));
    assertEquals("ALL", model.getAttribute("period"));

    verify(backendApiClient).get(anyString(), anyTypeRef());
  }

  @Test
  void listMissions_PeriodPast_ShouldRelayOnlyFinishedStatuses() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionPageController controller =
        new MissionPageController(
            new MissionBackendClient(backendApiClient),
            mock(FrontendAuthHelperService.class),
            PARALLEL);
    Model model = new ConcurrentModel();

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
    when(backendApiClient.get(uriCaptor.capture(), anyTypeRef()))
        .thenReturn(
            new de.greluc.krt.profit.basetool.frontend.model.PageResponse<>(
                Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList()));

    controller.listMissions(
        null, null, null, null, false, "PAST", null, null, null, model, mock(OidcUser.class));

    String uri = uriCaptor.getValue();
    assertTrue(uri.contains("status=COMPLETED&status=CANCELLED"));
    assertFalse(uri.contains("status=PLANNED"));
    assertFalse(uri.contains("status=ACTIVE"));
    assertEquals("PAST", model.getAttribute("period"));
  }

  @Test
  void listMissions_UnknownPeriod_ShouldFallBackToUpcoming() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionPageController controller =
        new MissionPageController(
            new MissionBackendClient(backendApiClient),
            mock(FrontendAuthHelperService.class),
            PARALLEL);
    Model model = new ConcurrentModel();

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
    when(backendApiClient.get(uriCaptor.capture(), anyTypeRef()))
        .thenReturn(
            new de.greluc.krt.profit.basetool.frontend.model.PageResponse<>(
                Collections.emptyList(), 0, 0, 0, 0, Collections.emptyList()));

    controller.listMissions(
        null, null, null, null, false, "SOMETIME", null, null, null, model, mock(OidcUser.class));

    String uri = uriCaptor.getValue();
    assertTrue(uri.contains("status=PLANNED&status=ACTIVE"));
    assertFalse(uri.contains("status=COMPLETED"));
    assertEquals("UPCOMING", model.getAttribute("period"));
    assertFalse((Boolean) model.getAttribute("showPast"));
  }

  @Test
  void missionDetail_ShouldFetchMissionAndFilteredJobTypes() {
    UUID id = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    FrontendAuthHelperService authHelper = mock(FrontendAuthHelperService.class);
    MissionPageController controller =
        new MissionPageController(new MissionBackendClient(backendApiClient), authHelper, PARALLEL);
    Model model = new ConcurrentModel();

    de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto mission =
        new de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto(
            id,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null,
            null);

    when(backendApiClient.get(eq("/api/v1/missions/{id}"), anyTypeRef(), eq(id)))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    String view = controller.missionDetail(id, model, null, null);

    assertEquals("mission-detail", view);
    verify(backendApiClient).get(eq("/api/v1/missions/{id}"), anyTypeRef(), eq(id));
    verify(backendApiClient).getCached(eq(CachedCatalog.JOB_TYPES_MISSION), anyTypeRef());
    verify(backendApiClient).getCached(eq(CachedCatalog.JOB_TYPES_CREW), anyTypeRef());
    verify(backendApiClient).getCached(eq(CachedCatalog.SQUADRONS_UNSORTED), anyTypeRef());
  }

  @Test
  void missionDetail_Guest_ShouldNotFetchFinanceOrRefineryOrders() {
    UUID id = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MissionPageController controller =
        new MissionPageController(
            new MissionBackendClient(backendApiClient),
            mock(FrontendAuthHelperService.class),
            PARALLEL);
    Model model = new ConcurrentModel();

    de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto mission =
        new de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto(
            id,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null,
            null);

    when(backendApiClient.get(eq("/api/v1/missions/{id}"), anyTypeRef(), eq(id)))
        .thenReturn(mission);
    when(backendApiClient.get(eq("/api/v1/missions/" + id + "/units?size=1000"), anyTypeRef()))
        .thenReturn(
            new de.greluc.krt.profit.basetool.frontend.model.PageResponse<>(
                Collections.emptyList(), 0, 10, 0, 0, Collections.emptyList()));
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    controller.missionDetail(id, model, null, null);

    verify(backendApiClient, never())
        .get(contains("/finance-entries"), anyTypeRef(), any(Object[].class));
    verify(backendApiClient, never())
        .get(contains("/refinery-orders"), anyTypeRef(), any(Object[].class));
    verify(backendApiClient, never())
        .get(contains("/finance-entries"), anyClass(), any(Object[].class));
  }

  @Test
  void missionDetail_Member_FetchesFinanceTrioViaParallelLoader() {
    UUID id = UUID.randomUUID();
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    FrontendAuthHelperService authHelper = mock(FrontendAuthHelperService.class);
    when(authHelper.isMemberOrAbove()).thenReturn(true);
    MissionPageController controller =
        new MissionPageController(new MissionBackendClient(backendApiClient), authHelper, PARALLEL);
    Model model = new ConcurrentModel();

    de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto mission =
        new de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto(
            id,
            "Test Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null,
            Collections.emptySet(),
            true,
            true,
            1L,
            1L,
            1L,
            1L,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null,
            null);

    when(backendApiClient.get(eq("/api/v1/missions/{id}"), anyTypeRef(), eq(id)))
        .thenReturn(mission);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.get(
            eq("/api/v1/missions/{id}/finance-entries/summary"),
            eq(de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceTotalsDto.class),
            eq(id)))
        .thenReturn(
            new de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceTotalsDto(
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                0L,
                java.math.BigDecimal.ZERO,
                0L));
    when(backendApiClient.get(
            eq("/api/v1/missions/{id}/finance-entries?size={size}"), anyTypeRef(), eq(id), eq(200)))
        .thenReturn(
            new de.greluc.krt.profit.basetool.frontend.model.PageResponse<>(
                Collections.emptyList(), 0, 200, 0, 0, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/refinery-orders/mission/{id}"), anyTypeRef(), eq(id)))
        .thenReturn(Collections.emptyList());

    controller.missionDetail(id, model, null, null);

    verify(backendApiClient)
        .get(
            eq("/api/v1/missions/{id}/finance-entries/summary"),
            eq(de.greluc.krt.profit.basetool.frontend.mission.model.MissionFinanceTotalsDto.class),
            eq(id));
    verify(backendApiClient)
        .get(
            eq("/api/v1/missions/{id}/finance-entries?size={size}"), anyTypeRef(), eq(id), eq(200));
    verify(backendApiClient).get(eq("/api/v1/refinery-orders/mission/{id}"), anyTypeRef(), eq(id));
  }
}
