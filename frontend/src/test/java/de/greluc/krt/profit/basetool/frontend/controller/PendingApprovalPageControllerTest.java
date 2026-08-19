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
    // The poll is what turns an open waiting page into the rejection page without a reload, so a
    // REJECTED verdict has to survive the relay rather than being flattened into "keep waiting".
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("REJECTED"));

    assertThat(controller.status().approvalStatus()).isEqualTo("REJECTED");
  }

  @Test
  void status_whenBackendUnavailable_reportsUnknownRatherThanFailing() {
    // The page polls every few seconds; a backend hiccup must degrade to "keep waiting", not to an
    // error surface on the one page a pending member is allowed to see.
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    assertThat(controller.status().approvalStatus()).isNull();
  }

  @Test
  void status_whenFallbackReturnsNoBody_reportsUnknown() {
    // Resilience4j's fallback hands back null rather than throwing.
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class)).thenReturn(null);

    assertThat(controller.status().approvalStatus()).isNull();
  }

  @Test
  void status_whenTokenIsGone_propagatesSoTheBrowserReauthenticates() {
    // Deliberately NOT swallowed: GlobalExceptionHandler turns this into the 401 +
    // X-Reauthenticate contract (REQ-SEC-012), so the page re-authenticates instead of polling a
    // session that can no longer reach the backend.
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
    // The regression: REJECTED is terminal (the backend answers a second decision with a 409), so
    // the waiting copy promises an approval that can no longer arrive.
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
    // Only reachable via a stale bookmark or a tab left open across the approval — an approved
    // member must not be shown a waiting page for an approval they already hold.
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
    // Redirect-loop regression. This page is only reached because BackendRoleSyncFilter believes
    // the caller is not approved, and it serves that belief from the session for up to 15 s without
    // re-reading. Redirecting to "/" while that stale PENDING verdict survives makes the filter
    // bounce the browser straight back here — and since neither hop touches the backend, the loop
    // runs at full speed into the browser's redirect cap instead of merely being slow. The literal
    // attribute names mirror the filter's own constants; if those are renamed, this test fails
    // loudly rather than silently stopping to test anything.
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
  void pendingApproval_withoutASession_stillRedirectsAnApprovedCaller() {
    // getSession(false) hands back null rather than creating one; the redirect must not NPE on it.
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));

    assertThat(controller.pendingApproval(new ConcurrentModel(), new MockHttpServletRequest()))
        .isEqualTo("redirect:/");
  }

  @Test
  void pendingApproval_forARejectedCaller_leavesTheSessionVerdictAlone() {
    // Only the ACTIVE branch contradicts the filter. Clearing on REJECTED would just buy an extra
    // backend read per request for a verdict that is already correct and terminal.
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
    // Fail-safe direction: an unreadable backend must never tell a still-pending member they were
    // declined. It also must not redirect them into the tool, which the filter would bounce back.
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
    // The render shares the poll's read, so it shares its re-authentication contract:
    // GlobalExceptionHandler answers a page request with a redirect into the Keycloak login flow.
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenThrow(new ReauthenticationRequiredException("token gone", null));

    assertThatThrownBy(
            () -> controller.pendingApproval(new ConcurrentModel(), new MockHttpServletRequest()))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }
}
