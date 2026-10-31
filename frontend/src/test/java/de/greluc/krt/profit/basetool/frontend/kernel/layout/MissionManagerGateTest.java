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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link MissionManagerGate}: the backend's pin-aware flag decides, an admin passes
 * without a call, the answer is memoised per request and any failure reads as not granted.
 */
@ExtendWith(MockitoExtension.class)
class MissionManagerGateTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private FrontendAuthHelperService authHelper;

  private MockHttpServletRequest request;
  private MissionManagerGate gate;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    gate = new MissionManagerGate(backendApiClient, authHelper, request);
  }

  @Test
  void admin_passesWithoutABackendCall() {
    when(authHelper.isAdmin()).thenReturn(true);

    assertTrue(gate.isGranted());
    verify(backendApiClient, never())
        .get(MissionManagerGate.CAPABILITIES_PATH, CapabilitiesResponse.class);
  }

  @Test
  void grantInTheActiveContext_isGranted() {
    when(backendApiClient.get(MissionManagerGate.CAPABILITIES_PATH, CapabilitiesResponse.class))
        .thenReturn(LayoutResponses.missionManager());

    assertTrue(gate.isGranted());
  }

  @Test
  void noGrantInTheActiveContext_isRefused() {
    when(backendApiClient.get(MissionManagerGate.CAPABILITIES_PATH, CapabilitiesResponse.class))
        .thenReturn(CapabilitiesResponse.NONE);

    assertFalse(gate.isGranted());
  }

  @Test
  void backendFailure_failsClosed() {
    when(backendApiClient.get(MissionManagerGate.CAPABILITIES_PATH, CapabilitiesResponse.class))
        .thenThrow(new IllegalStateException("backend down"));

    assertFalse(gate.isGranted());
  }

  @Test
  void answer_isReadOncePerRequest() {
    when(backendApiClient.get(MissionManagerGate.CAPABILITIES_PATH, CapabilitiesResponse.class))
        .thenReturn(LayoutResponses.missionManager());

    assertTrue(gate.isGranted());
    assertTrue(gate.isGranted());
    verify(backendApiClient, times(1))
        .get(MissionManagerGate.CAPABILITIES_PATH, CapabilitiesResponse.class);
  }
}
