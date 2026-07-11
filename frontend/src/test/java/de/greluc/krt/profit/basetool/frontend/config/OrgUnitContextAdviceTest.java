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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.config.OrgUnitContextAdvice.ActiveOrgUnitResponse;
import de.greluc.krt.profit.basetool.frontend.controller.MeFrontendController;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    return new OrgUnitContextAdvice(backendApiClient, authHelper);
  }

  @Test
  void availableSquadrons_routesSquadronCatalogueThroughCache() {
    // REQ-DATA-007: the squadron catalogue is a slow-changing global list fetched on every
    // authenticated render; it must go through the 10-min STATIC_DATA_CACHE (getCached), not a
    // per-render plain GET. getCached is unstubbed (returns null → advice degrades to empty); the
    // assertion is about the routing, not the payload.
    when(authHelper.isAuthenticated()).thenReturn(true);

    advice().availableSquadrons();

    verify(backendApiClient).getCached(eq(CachedCatalog.SQUADRONS), anyTypeRef());
    verify(backendApiClient, never())
        .get(eq("/api/v1/squadrons?size=1000&sort=name,asc"), anyTypeRef());
  }

  @Test
  void adminSwitcher_routesSquadronAndSpecialCommandCataloguesThroughCache_notPlainGet() {
    // REQ-DATA-007: the admin switcher's squadron AND special-command catalogues both go through
    // getCached (URI-keyed STATIC_DATA_CACHE), never a plain GET. The SK catalogue became cacheable
    // once every SK lifecycle mutation wired clearStaticDataCache()
    // (AdminSpecialCommandsPageController
    // + SpecialCommandAdminProxyController); this pins that both are cached now.
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(true);

    advice().availableOrgUnits();

    verify(backendApiClient).getCached(eq(CachedCatalog.SQUADRONS), anyTypeRef());
    verify(backendApiClient, never())
        .get(eq("/api/v1/squadrons?size=1000&sort=name,asc"), anyTypeRef());
    verify(backendApiClient).getCached(eq(CachedCatalog.SPECIAL_COMMANDS), anyTypeRef());
    verify(backendApiClient, never())
        .get(eq("/api/v1/special-commands?size=1000&sort=name,asc"), anyTypeRef());
  }

  @Test
  void activeSquadronId_sessionPin_isReturned() {
    // Branch 1: an active session pin (set by MeFrontendController) wins outright — no isAdmin
    // check, no backend round-trip. A regression here makes an admin's pinned OrgUnit invisible to
    // the layout (title/badge show "Alle Staffeln" while data is scoped to the pin).
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
    // Branch 2: an authenticated admin with no session pin resolves to null (all-scopes mode) and
    // must NOT fall through to the non-admin backend lookup.
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(request.getSession(false)).thenReturn(null);
    when(authHelper.isAdmin()).thenReturn(true);

    assertNull(advice().activeSquadronId(request));
    verify(backendApiClient, never()).get(any(String.class), anyClass());
  }

  @Test
  void activeSquadronId_nonAdmin_fallsBackToBackendActiveOrgUnit() {
    // Branch 3: a non-admin without a session pin resolves the persistent home Staffel via the
    // backend GET /api/v1/me/active-org-unit. A break here stops resolving the home staffel.
    UUID home = UUID.randomUUID();
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(request.getSession(false)).thenReturn(null);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get("/api/v1/me/active-org-unit", ActiveOrgUnitResponse.class))
        .thenReturn(new ActiveOrgUnitResponse(home));

    assertEquals(home, advice().activeSquadronId(request));
  }

  @Test
  void activeSquadronId_backendFailure_degradesToNull() {
    // Branch 4: a backend hiccup on the non-admin fallback degrades silently to null rather than
    // 500ing the layout render.
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(request.getSession(false)).thenReturn(null);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get("/api/v1/me/active-org-unit", ActiveOrgUnitResponse.class))
        .thenThrow(new RuntimeException("boom"));

    assertNull(advice().activeSquadronId(request));
  }
}
