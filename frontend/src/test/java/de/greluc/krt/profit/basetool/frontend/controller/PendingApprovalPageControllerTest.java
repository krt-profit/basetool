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

/**
 * Unit tests for {@link PendingApprovalPageController}'s status poll (REQ-SEC-017), the endpoint
 * that lets the waiting page forward a member into the tool the moment an admin approves instead of
 * leaving them to discover it by logging out and back in.
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
  void pendingApproval_rendersTheWaitingPage() {
    assertThat(controller.pendingApproval()).isEqualTo("pending-approval");
  }
}
