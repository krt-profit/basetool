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
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.controller.MeFrontendController;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link OrgUnitContextAdvice}. Two groups: (1) the slow-changing catalogue reads —
 * both {@code availableSquadrons()} and the admin switcher's {@code availableOrgUnits()} must route
 * the Squadron / SpecialCommand catalogues through the URI-keyed {@code getCached} path
 * (REQ-DATA-007), never a plain per-render GET; (2) the {@code activeSquadronId} resolver and its
 * four branches (session pin, admin-without-pin → all-scopes null, non-admin → backend
 * active-org-unit fallback, and backend-failure → null) — the value every OrgUnit-derived attribute
 * (title, badge, all-squadrons mode, promotion visibility) hangs off.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitContextAdviceTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private FrontendAuthHelperService authHelper;
  @Mock private HttpServletRequest request;

  private OrgUnitContextAdvice advice() {
    return new OrgUnitContextAdvice(
        backendApiClient, new LayoutContextLoader(backendApiClient, authHelper), authHelper);
  }

  @Test
  void availableSquadrons_routesSquadronCatalogueThroughCache() {
    when(authHelper.isAuthenticated()).thenReturn(true);

    advice().availableSquadrons(new MockHttpServletRequest());

    verify(backendApiClient).getCached(eq(CachedCatalog.SQUADRONS), anyTypeRef());
    verify(backendApiClient, never())
        .get(eq("/api/v1/squadrons?size=1000&sort=name,asc"), anyTypeRef());
  }

  @Test
  void switcher_asksTheServerWhichOrgUnitsMayBePinned_ratherThanBranchingItself() {
    when(authHelper.isAuthenticated()).thenReturn(true);

    advice().availableOrgUnits(new MockHttpServletRequest());

    verify(backendApiClient).get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class);
    verify(backendApiClient, never()).get(eq("/api/v1/me/org-units"), anyTypeRef());
    verify(backendApiClient, never()).getCached(eq(CachedCatalog.SQUADRONS), anyTypeRef());
    verify(backendApiClient, never()).getCached(eq(CachedCatalog.SPECIAL_COMMANDS), anyTypeRef());
  }

  @Test
  void switcher_returnsEmptyForAnAnonymousCaller_withoutAskingTheBackend() {
    when(authHelper.isAuthenticated()).thenReturn(false);

    assertTrue(advice().availableOrgUnits(new MockHttpServletRequest()).isEmpty());
    verifyNoInteractions(backendApiClient);
  }

  @Test
  void activeSquadronId_sessionPin_isReturned() {
    UUID pinned = UUID.randomUUID();
    when(authHelper.isAuthenticated()).thenReturn(true);
    HttpSession session = mock(HttpSession.class);
    when(request.getSession(false)).thenReturn(session);
    when(session.getAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY)).thenReturn(pinned);

    assertEquals(pinned, advice().activeSquadronId(request));
    verify(backendApiClient, never()).get(any(String.class), anyClass());
  }

  @Test
  void activeSquadronId_adminWithoutPin_isNull() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(request.getSession(false)).thenReturn(null);
    when(authHelper.isAdmin()).thenReturn(true);

    assertNull(advice().activeSquadronId(request));
    verify(backendApiClient, never()).get(any(String.class), anyClass());
  }

  @Test
  void activeSquadronId_nonAdmin_fallsBackToBackendActiveOrgUnit() {
    UUID home = UUID.randomUUID();
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(request.getSession(false)).thenReturn(null);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.activeOrgUnit(home));

    assertEquals(home, advice().activeSquadronId(request));
  }

  @Test
  void activeSquadronId_backendFailure_degradesToNull() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(request.getSession(false)).thenReturn(null);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenThrow(new RuntimeException("boom"));

    assertNull(advice().activeSquadronId(request));
  }
}
