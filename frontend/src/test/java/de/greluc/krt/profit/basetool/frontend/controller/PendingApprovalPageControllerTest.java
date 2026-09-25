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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.exception.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.model.dto.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Unit tests for {@link PendingApprovalPageController} (REQ-SEC-017) — the status poll that lets
 * the waiting page forward a member into the tool the moment an admin approves, and the render
 * branch that decides which copy that page shows.
 *
 * <p>The render branch is the half that was missing: the routing filter sends {@code PENDING} and
 * {@code REJECTED} to the same path because both are equally access-less, so a rejected
 * registration was shown "waiting for an administrator" indefinitely and reported the rejection as
 * a stuck approval. All three approval states are pinned here, plus the unreadable-backend case,
 * which must degrade to the waiting copy rather than accusing a pending member of being declined.
 */
class PendingApprovalPageControllerTest {

  private static final String REGISTRATION_STATUS = "/api/v1/users/me/registration-status";

  private BackendApiClient backendApiClient;
  private PendingApprovalPageController controller;

  /** Wires a fresh backend-client mock into the controller under test. */
  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    controller = new PendingApprovalPageController(backendApiClient);
  }

  @Test
  void status_relaysTheBackendVerdict() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));

    assertThat(controller.status().approvalStatus()).isEqualTo("ACTIVE");
  }

  @Test
  void status_relaysARejection() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("REJECTED"));

    assertThat(controller.status().approvalStatus()).isEqualTo("REJECTED");
  }

  @Test
  void status_whenBackendUnavailable_reportsUnknownRatherThanFailing() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    assertThat(controller.status().approvalStatus()).isNull();
  }

  @Test
  void status_whenFallbackReturnsNoBody_reportsUnknown() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class)).thenReturn(null);

    assertThat(controller.status().approvalStatus()).isNull();
  }

  @Test
  void status_whenTokenIsGone_propagatesSoTheBrowserReauthenticates() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenThrow(new ReauthenticationRequiredException("token gone", null));

    assertThatThrownBy(() -> controller.status())
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void pendingApproval_forAPendingRegistration_rendersTheWaitingPage() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("PENDING"));
    Model model = new ConcurrentModel();

    assertThat(controller.pendingApproval(model, new MockHttpServletRequest()))
        .isEqualTo("pending-approval");
    assertThat(model.getAttribute(PendingApprovalPageController.MODEL_REJECTED))
        .as("a pending registration gets the waiting copy")
        .isEqualTo(Boolean.FALSE);
  }

  @Test
  void pendingApproval_forARejectedRegistration_selectsTheRejectionCopy() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("REJECTED"));
    Model model = new ConcurrentModel();

    assertThat(controller.pendingApproval(model, new MockHttpServletRequest()))
        .isEqualTo("pending-approval");
    assertThat(model.getAttribute(PendingApprovalPageController.MODEL_REJECTED))
        .as("a rejected registration gets the rejection copy, not the waiting copy")
        .isEqualTo(Boolean.TRUE);
  }

  @Test
  void pendingApproval_forAnApprovedCaller_redirectsIntoTheTool() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));
    Model model = new ConcurrentModel();

    assertThat(controller.pendingApproval(model, new MockHttpServletRequest()))
        .isEqualTo("redirect:/");
    assertThat(model.getAttribute(PendingApprovalPageController.MODEL_REJECTED))
        .as("no copy is selected on the redirect path")
        .isNull();
  }

  @Test
  void pendingApproval_forAnApprovedCaller_clearsTheStaleVerdictSoTheFilterCannotBounceThemBack() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("BACKEND_APPROVAL_STATE", "PENDING");
    session.setAttribute("BACKEND_APPROVAL_CHECKED_AT", System.currentTimeMillis());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);

    assertThat(controller.pendingApproval(new ConcurrentModel(), request)).isEqualTo("redirect:/");
    assertThat(session.getAttributeNames().hasMoreElements())
        .as("no cached approval verdict survives the redirect")
        .isFalse();
  }

  @Test
  void pendingApproval_forARoleLessCaller_staysPutEvenThoughTheRegistrationIsActive() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("BACKEND_APPROVAL_STATE", "NO_ROLE");
    session.setAttribute("BACKEND_APPROVAL_CHECKED_AT", System.currentTimeMillis());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    Model model = new ConcurrentModel();

    assertThat(controller.pendingApproval(model, request)).isEqualTo("pending-approval");
    assertThat(model.getAttribute(PendingApprovalPageController.MODEL_NO_ROLE))
        .as("the role-less copy, not the waiting copy: this member has already been approved")
        .isEqualTo(Boolean.TRUE);
    assertThat(model.getAttribute(PendingApprovalPageController.MODEL_REJECTED)).isEqualTo(false);
    assertThat(session.getAttribute("BACKEND_APPROVAL_STATE"))
        .as("the verdict that routed them here must survive, or the next request re-derives it")
        .isEqualTo("NO_ROLE");
  }

  @Test
  void pendingApproval_forARoleLessCallerWhoseRegistrationWasRejected_showsTheRejection() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("REJECTED"));
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("BACKEND_APPROVAL_STATE", "NO_ROLE");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    Model model = new ConcurrentModel();

    assertThat(controller.pendingApproval(model, request)).isEqualTo("pending-approval");
    assertThat(model.getAttribute(PendingApprovalPageController.MODEL_REJECTED)).isEqualTo(true);
    assertThat(model.getAttribute(PendingApprovalPageController.MODEL_NO_ROLE)).isEqualTo(false);
  }

  @Test
  void pendingApproval_withoutASession_stillRedirectsAnApprovedCaller() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));

    assertThat(controller.pendingApproval(new ConcurrentModel(), new MockHttpServletRequest()))
        .isEqualTo("redirect:/");
  }

  @Test
  void pendingApproval_forARejectedCaller_leavesTheSessionVerdictAlone() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("REJECTED"));
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("BACKEND_APPROVAL_STATE", "REJECTED");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);

    controller.pendingApproval(new ConcurrentModel(), request);

    assertThat(session.getAttribute("BACKEND_APPROVAL_STATE")).isEqualTo("REJECTED");
  }

  @Test
  void pendingApproval_whenBackendUnavailable_keepsTheWaitingCopy() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenThrow(new BackendServiceException("backend down", null, 503));
    Model model = new ConcurrentModel();

    assertThat(controller.pendingApproval(model, new MockHttpServletRequest()))
        .isEqualTo("pending-approval");
    assertThat(model.getAttribute(PendingApprovalPageController.MODEL_REJECTED))
        .isEqualTo(Boolean.FALSE);
  }

  @Test
  void pendingApproval_whenFallbackReturnsNoBody_keepsTheWaitingCopy() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class)).thenReturn(null);
    Model model = new ConcurrentModel();

    assertThat(controller.pendingApproval(model, new MockHttpServletRequest()))
        .isEqualTo("pending-approval");
    assertThat(model.getAttribute(PendingApprovalPageController.MODEL_REJECTED))
        .isEqualTo(Boolean.FALSE);
  }

  @Test
  void pendingApproval_whenTokenIsGone_propagatesSoTheBrowserReauthenticates() {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenThrow(new ReauthenticationRequiredException("token gone", null));

    assertThatThrownBy(
            () -> controller.pendingApproval(new ConcurrentModel(), new MockHttpServletRequest()))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }
}
