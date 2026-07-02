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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.FinanceType;
import de.greluc.krt.profit.basetool.frontend.model.form.MissionFinanceEntryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Unit tests for {@link MissionFinancePageController}. The controller had 2% line / 0% branch
 * coverage. Three small endpoints (add / update / delete) each carry a happy-path / error-path /
 * validation-error split that all follow the same shape: success toast + redirect, failure toast +
 * redirect, or BindingResult-direct-render delegating to {@link MissionPageController}.
 */
class MissionFinancePageControllerTest {

  private BackendApiClient backendApiClient;
  private MissionPageController missionPageController;
  private MissionFinancePageController controller;
  private RedirectAttributes redirectAttributes;
  private OidcUser principal;

  private static final UUID MISSION_ID = UUID.randomUUID();
  private static final UUID ENTRY_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    missionPageController = mock(MissionPageController.class);
    controller = new MissionFinancePageController(backendApiClient, missionPageController);
    redirectAttributes = new RedirectAttributesModelMap();
    principal = mock(OidcUser.class);
  }

  // ---------------------------------------------------------------
  // addFinanceEntry
  // ---------------------------------------------------------------

  @Nested
  class AddFinanceEntryTests {

    @Test
    void validationErrors_delegateToMissionDetail_andSetOpenModal() {
      // The validation-error path must NOT redirect — it re-renders the
      // mission-detail view directly so BindingResult stays request-scoped
      // (no Redis FlashMap round-trip). The "openModal" attribute is set so
      // the front-end re-opens the same dialog the user was filling in.
      Model model = new ConcurrentModel();
      MissionFinanceEntryForm form = newForm(FinanceType.INCOME, BigDecimal.TEN);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(true);
      when(missionPageController.missionDetail(eq(MISSION_ID), eq(model), eq(principal), isNull()))
          .thenReturn("mission-detail");

      String view =
          controller.addFinanceEntry(MISSION_ID, form, br, model, redirectAttributes, principal);

      assertEquals(
          "mission-detail",
          view,
          "validation error -> direct render via missionDetail, NOT redirect");
      assertEquals("finance-entry-modal", model.getAttribute("openModal"));
      verify(backendApiClient, never()).post(anyString(), any(), any(), anyBoolean());
    }

    @Test
    void happyPath_postsBodyAndRedirects() {
      Model model = new ConcurrentModel();
      MissionFinanceEntryForm form = newForm(FinanceType.INCOME, new BigDecimal("250.00"));
      UUID participantId = UUID.randomUUID();
      form.setParticipantId(participantId);
      form.setNote("commodity sale");
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);

      String view =
          controller.addFinanceEntry(MISSION_ID, form, br, model, redirectAttributes, principal);

      assertEquals("redirect:/missions/" + MISSION_ID, view);
      // POST body shape: missionId + participantId + note + type + amount.
      ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.captor();
      verify(backendApiClient)
          .post(
              eq("/api/v1/finance-entries"),
              bodyCaptor.capture(),
              eq(Void.class),
              /* isPublic= */ eq(false));
      Map<String, Object> body = bodyCaptor.getValue();
      assertEquals(MISSION_ID, body.get("missionId"));
      assertEquals(participantId, body.get("participantId"));
      assertEquals("commodity sale", body.get("note"));
      assertEquals(FinanceType.INCOME, body.get("type"));
      assertEquals(new BigDecimal("250.00"), body.get("amount"));
      assertEquals(
          "notification.success.save", redirectAttributes.getFlashAttributes().get("successToast"));
    }

    @Test
    void anonymousCaller_passesIsPublicTrueToBackend() {
      // principal == null -> isPublic=true on the backend call (anonymous
      // user submitting from a public mission RSVP form).
      Model model = new ConcurrentModel();
      MissionFinanceEntryForm form = newForm(FinanceType.INCOME, BigDecimal.TEN);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);

      controller.addFinanceEntry(
          MISSION_ID, form, br, model, redirectAttributes, /* principal= */ null);

      verify(backendApiClient).post(anyString(), any(), eq(Void.class), eq(true));
    }

    @Test
    void backendFailure_addsErrorToast_andRedirects() {
      Model model = new ConcurrentModel();
      MissionFinanceEntryForm form = newForm(FinanceType.EXPENSE, BigDecimal.TEN);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);
      doThrow(new RuntimeException("backend down"))
          .when(backendApiClient)
          .post(anyString(), any(), any(), anyBoolean());

      String view =
          controller.addFinanceEntry(MISSION_ID, form, br, model, redirectAttributes, principal);

      assertEquals("redirect:/missions/" + MISSION_ID, view);
      assertEquals("error.finance.add", redirectAttributes.getFlashAttributes().get("errorToast"));
      assertNull(redirectAttributes.getFlashAttributes().get("successToast"));
    }
  }

  // ---------------------------------------------------------------
  // updateFinanceEntry
  // ---------------------------------------------------------------

  @Nested
  class UpdateFinanceEntryTests {

    @Test
    void validationErrors_delegateToMissionDetail_withEditModalContext() {
      Model model = new ConcurrentModel();
      MissionFinanceEntryForm form = newForm(FinanceType.INCOME, BigDecimal.TEN);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(true);
      when(missionPageController.missionDetail(eq(MISSION_ID), eq(model), eq(principal), isNull()))
          .thenReturn("mission-detail");

      String view =
          controller.updateFinanceEntry(
              MISSION_ID, ENTRY_ID, form, br, model, redirectAttributes, principal);

      assertEquals("mission-detail", view);
      assertEquals("edit-finance-entry-modal", model.getAttribute("openModal"));
      // The action attribute must include the actual entry id so the front-end
      // posts back to the right edit endpoint.
      assertEquals(
          "/missions/" + MISSION_ID + "/finance-entries/" + ENTRY_ID + "/update",
          model.getAttribute("modalAction"));
      verify(backendApiClient, never()).put(anyString(), any(), any(), anyBoolean());
    }

    @Test
    void happyPath_putsBodyAndRedirects() {
      Model model = new ConcurrentModel();
      MissionFinanceEntryForm form = newForm(FinanceType.EXPENSE, new BigDecimal("99.99"));
      form.setNote("repairs");
      form.setVersion(3L);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);

      String view =
          controller.updateFinanceEntry(
              MISSION_ID, ENTRY_ID, form, br, model, redirectAttributes, principal);

      assertEquals("redirect:/missions/" + MISSION_ID, view);
      // PUT body: note + type + amount + version. NO missionId/participantId
      // (immutable after creation).
      ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.captor();
      verify(backendApiClient)
          .put(
              eq("/api/v1/finance-entries/" + ENTRY_ID),
              bodyCaptor.capture(),
              eq(Void.class),
              eq(false));
      Map<String, Object> body = bodyCaptor.getValue();
      assertEquals("repairs", body.get("note"));
      assertEquals(FinanceType.EXPENSE, body.get("type"));
      assertEquals(new BigDecimal("99.99"), body.get("amount"));
      assertEquals(
          3L, body.get("version"), "version must be propagated for the optimistic-lock check");
      assertNull(
          body.get("missionId"), "update body must NOT carry missionId — that's not editable");
      assertEquals(
          "notification.success.save", redirectAttributes.getFlashAttributes().get("successToast"));
    }

    @Test
    void backendFailure_addsErrorToast() {
      Model model = new ConcurrentModel();
      MissionFinanceEntryForm form = newForm(FinanceType.INCOME, BigDecimal.TEN);
      BindingResult br = mock(BindingResult.class);
      when(br.hasErrors()).thenReturn(false);
      doThrow(new RuntimeException("409"))
          .when(backendApiClient)
          .put(anyString(), any(), any(), anyBoolean());

      String view =
          controller.updateFinanceEntry(
              MISSION_ID, ENTRY_ID, form, br, model, redirectAttributes, principal);

      assertEquals("redirect:/missions/" + MISSION_ID, view);
      assertEquals(
          "error.finance.update", redirectAttributes.getFlashAttributes().get("errorToast"));
    }
  }

  // ---------------------------------------------------------------
  // deleteFinanceEntry
  // ---------------------------------------------------------------

  @Nested
  class DeleteFinanceEntryTests {

    @Test
    void happyPath_callsBackendDelete_andRedirects() {
      String view =
          controller.deleteFinanceEntry(MISSION_ID, ENTRY_ID, principal, redirectAttributes);

      assertEquals("redirect:/missions/" + MISSION_ID, view);
      verify(backendApiClient).delete("/api/v1/finance-entries/" + ENTRY_ID, Void.class, false);
      assertEquals(
          "notification.success.delete",
          redirectAttributes.getFlashAttributes().get("successToast"));
    }

    @Test
    void backendFailure_addsErrorToast() {
      doThrow(new RuntimeException("404"))
          .when(backendApiClient)
          .delete(anyString(), any(), anyBoolean());

      String view =
          controller.deleteFinanceEntry(MISSION_ID, ENTRY_ID, principal, redirectAttributes);

      assertEquals("redirect:/missions/" + MISSION_ID, view);
      assertEquals(
          "error.finance.delete", redirectAttributes.getFlashAttributes().get("errorToast"));
      assertNull(redirectAttributes.getFlashAttributes().get("successToast"));
    }
  }

  // ---------------------------------------------------------------
  // AJAX endpoints (#574): in-place create / update / delete
  // ---------------------------------------------------------------

  @Nested
  class AjaxEndpointTests {

    @Test
    void addAjax_stampsMissionId_postsBody_andReturns200() {
      Map<String, Object> body = new HashMap<>();
      body.put("participantId", UUID.randomUUID().toString());
      body.put("type", "INCOME");
      body.put("amount", "250");
      when(backendApiClient.post(eq("/api/v1/finance-entries"), any(), eq(Object.class), eq(false)))
          .thenReturn(Map.of("id", ENTRY_ID.toString()));

      ResponseEntity<Object> resp = controller.addFinanceEntryAjax(MISSION_ID, body, principal);

      assertEquals(200, resp.getStatusCode().value());
      assertEquals(MISSION_ID, body.get("missionId"), "missionId is stamped from the path");
    }

    @Test
    void addAjax_anonymousCaller_passesIsPublicTrue() {
      Map<String, Object> body = new HashMap<>();
      controller.addFinanceEntryAjax(MISSION_ID, body, /* principal= */ null);
      verify(backendApiClient)
          .post(eq("/api/v1/finance-entries"), any(), eq(Object.class), eq(true));
    }

    @Test
    void updateAjax_putsBodyVerbatim_andReturns200() {
      Map<String, Object> body = new HashMap<>();
      body.put("amount", "99");
      body.put("version", 3);
      when(backendApiClient.put(
              eq("/api/v1/finance-entries/" + ENTRY_ID), any(), eq(Object.class), eq(false)))
          .thenReturn(Map.of("id", ENTRY_ID.toString()));

      ResponseEntity<Object> resp = controller.updateFinanceEntryAjax(MISSION_ID, ENTRY_ID, body);

      assertEquals(200, resp.getStatusCode().value());
      verify(backendApiClient)
          .put(eq("/api/v1/finance-entries/" + ENTRY_ID), eq(body), eq(Object.class), eq(false));
    }

    @Test
    void deleteAjax_callsBackendDelete_andReturns204() {
      ResponseEntity<Object> resp = controller.deleteFinanceEntryAjax(MISSION_ID, ENTRY_ID);

      assertEquals(204, resp.getStatusCode().value());
      verify(backendApiClient).delete("/api/v1/finance-entries/" + ENTRY_ID, Void.class, false);
    }
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  private static MissionFinanceEntryForm newForm(FinanceType type, BigDecimal amount) {
    MissionFinanceEntryForm form = new MissionFinanceEntryForm();
    form.setMissionId(MISSION_ID);
    form.setType(type);
    form.setAmount(amount);
    return form;
  }
}
