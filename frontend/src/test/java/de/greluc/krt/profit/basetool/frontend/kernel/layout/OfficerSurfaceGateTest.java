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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link OfficerSurfaceGate}: an admin passes, everyone else follows the backend's
 * officer flags of the active context, and a failed read fails closed.
 */
@ExtendWith(MockitoExtension.class)
class OfficerSurfaceGateTest {

  private final MockHttpServletRequest request = new MockHttpServletRequest();

  @Mock private LayoutContextLoader layoutContextLoader;
  @Mock private FrontendAuthHelperService authHelper;

  private OfficerSurfaceGate gate() {
    return new OfficerSurfaceGate(layoutContextLoader, authHelper, request);
  }

  private void backendOffers(CapabilitiesResponse capabilities) {
    when(authHelper.isAdmin()).thenReturn(false);
    when(layoutContextLoader.capabilities(request)).thenReturn(capabilities);
  }

  @Test
  void anAdminPassesEveryGateWithoutAnyBackendRead() {
    when(authHelper.isAdmin()).thenReturn(true);

    OfficerSurfaceGate gate = gate();

    assertThat(gate.canOpenLeitung()).isTrue();
    assertThat(gate.canManagePromotions()).isTrue();
    assertThat(gate.hasOfficerReach()).isTrue();
  }

  @Test
  void aCallerWithOfficerReachInTheActiveContextPassesEveryGate() {
    backendOffers(LayoutResponses.officerReach(true).capabilities());

    OfficerSurfaceGate gate = gate();

    assertThat(gate.canOpenLeitung()).isTrue();
    assertThat(gate.canManagePromotions()).isTrue();
    assertThat(gate.hasOfficerReach()).isTrue();
  }

  @Test
  void aCallerWithoutOfficerReachFailsEveryGate() {
    backendOffers(LayoutResponses.officerReach(false).capabilities());

    OfficerSurfaceGate gate = gate();

    assertThat(gate.canOpenLeitung()).isFalse();
    assertThat(gate.canManagePromotions()).isFalse();
    assertThat(gate.hasOfficerReach()).isFalse();
  }

  @Test
  void aFailedBackendReadFailsClosed() {
    backendOffers(CapabilitiesResponse.NONE);

    assertThat(gate().canManagePromotions()).isFalse();
  }
}
