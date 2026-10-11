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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link CapabilityFlagsAdvice}: the shared {@code meCapabilities} resolver (all-on
 * for admins, all-off for anonymous callers, fail-closed on error) and the sidebar flags derived
 * from it.
 */
@ExtendWith(MockitoExtension.class)
class CapabilityFlagsAdviceTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private FrontendAuthHelperService authHelper;

  private CapabilityFlagsAdvice advice() {
    return new CapabilityFlagsAdvice(
        new LayoutContextLoader(backendApiClient, authHelper), authHelper);
  }

  @Test
  void meCapabilities_anonymous_allFalse_withoutBackendCall() {
    when(authHelper.isAuthenticated()).thenReturn(false);

    CapabilitiesResponse caps = advice().meCapabilities(new MockHttpServletRequest());

    assertFalse(caps.canSeeBlueprintOverview());
    assertFalse(caps.canViewJobOrders());
    verify(backendApiClient, never()).get(any(String.class), anyClass());
  }

  @Test
  void meCapabilities_admin_roleGatesTrue_stolenSwitchFromBackend() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.stolenMarking(true));

    CapabilitiesResponse caps = advice().meCapabilities(new MockHttpServletRequest());

    assertTrue(caps.canSeeBlueprintOverview());
    assertTrue(caps.canViewJobOrders());
    assertTrue(caps.canViewOwnJobOrders());
    assertTrue(caps.canMarkStolen());
  }

  @Test
  void meCapabilities_admin_stolenSwitchOff_isOffForTheAdminToo() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.stolenMarking(false));

    CapabilitiesResponse caps = advice().meCapabilities(new MockHttpServletRequest());

    assertTrue(caps.canSeeBlueprintOverview());
    assertTrue(caps.canViewJobOrders());
    assertFalse(caps.canMarkStolen());
  }

  @Test
  void meCapabilities_admin_backendFails_roleGatesStayOn_stolenSwitchOff() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenThrow(new RuntimeException("boom"));

    CapabilitiesResponse caps = advice().meCapabilities(new MockHttpServletRequest());

    assertTrue(caps.canViewJobOrders());
    assertFalse(caps.canMarkStolen());
  }

  @Test
  void meCapabilities_nonAdmin_stolenSwitchFromBackend() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.stolenMarking(true));

    CapabilitiesResponse caps = advice().meCapabilities(new MockHttpServletRequest());

    assertTrue(caps.canMarkStolen());
    assertFalse(caps.canViewJobOrders());
  }

  @Test
  void meCapabilities_nonAdmin_reflectsBackend() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.capabilities(true, false, false));

    CapabilitiesResponse caps = advice().meCapabilities(new MockHttpServletRequest());

    assertTrue(caps.canSeeBlueprintOverview());
    assertFalse(caps.canViewJobOrders());
  }

  @Test
  void meCapabilities_nonAdmin_backendFails_failsClosed() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenThrow(new RuntimeException("boom"));

    CapabilitiesResponse caps = advice().meCapabilities(new MockHttpServletRequest());

    assertFalse(caps.canSeeBlueprintOverview());
    assertFalse(caps.canViewJobOrders());
  }

  @Test
  void derivedFlags_readFromCapabilities() {
    assertTrue(
        advice().canSeeBlueprintOverview(new CapabilitiesResponse(true, false, false, false)));
    assertFalse(
        advice().canSeeBlueprintOverview(new CapabilitiesResponse(false, true, false, false)));
    assertTrue(advice().canViewJobOrders(new CapabilitiesResponse(false, true, false, false)));
    assertFalse(advice().canViewJobOrders(new CapabilitiesResponse(true, false, false, false)));
    assertTrue(advice().canViewOwnJobOrders(new CapabilitiesResponse(false, false, true, false)));
    assertFalse(advice().canViewOwnJobOrders(new CapabilitiesResponse(false, false, false, false)));
    assertTrue(advice().canMarkStolen(new CapabilitiesResponse(false, false, false, true)));
    assertFalse(advice().canMarkStolen(new CapabilitiesResponse(true, true, true, false)));
  }

  @Test
  void derivedFlags_nullCapabilities_areFalse() {
    assertFalse(advice().canSeeBlueprintOverview(null));
    assertFalse(advice().canViewJobOrders(null));
    assertFalse(advice().canMarkStolen(null));
  }
}
