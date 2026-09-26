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

package de.greluc.krt.profit.basetool.frontend.config;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.config.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
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
  void meCapabilities_admin_allTrue_withoutBackendCall() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(true);

    CapabilitiesResponse caps = advice().meCapabilities(new MockHttpServletRequest());

    assertTrue(caps.canSeeBlueprintOverview());
    assertTrue(caps.canViewJobOrders());
    verify(backendApiClient, never()).get(any(String.class), anyClass());
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
    assertTrue(advice().canSeeBlueprintOverview(new CapabilitiesResponse(true, false, false)));
    assertFalse(advice().canSeeBlueprintOverview(new CapabilitiesResponse(false, true, false)));
    assertTrue(advice().canViewJobOrders(new CapabilitiesResponse(false, true, false)));
    assertFalse(advice().canViewJobOrders(new CapabilitiesResponse(true, false, false)));
    assertTrue(advice().canViewOwnJobOrders(new CapabilitiesResponse(false, false, true)));
    assertFalse(advice().canViewOwnJobOrders(new CapabilitiesResponse(false, false, false)));
  }

  @Test
  void derivedFlags_nullCapabilities_areFalse() {
    assertFalse(advice().canSeeBlueprintOverview(null));
    assertFalse(advice().canViewJobOrders(null));
  }
}
