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
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.ContextKind;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.kernel.web.ActiveSquadronContext;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link OrgUnitContextAdvice}: the catalogue reads go through the cached path
 * (REQ-DATA-007), and the context attributes show the backend's effective context, never a context
 * of the frontend's own (REQ-ORG-024).
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitContextAdviceTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private FrontendAuthHelperService authHelper;

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
    verify(backendApiClient, never()).get(eq("/api/v1/org-units/me/switchable"), anyTypeRef());
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
  void activeSquadronId_isTheOneUnitOfTheBackendsEffectiveContext() {
    UUID pinned = UUID.randomUUID();
    stubLayout(LayoutResponses.context(ContextKind.ORG_UNIT, pinned, List.of()));

    assertEquals(pinned, advice().activeSquadronId(new MockHttpServletRequest()));
  }

  @Test
  void activeSquadronId_unpinnedMemberOfSeveralUnits_isNullSoAllMyOrgUnitsIsSelected() {
    stubLayout(LayoutResponses.context(ContextKind.ALL_MY_ORG_UNITS, null, List.of()));
    MockHttpServletRequest request = new MockHttpServletRequest();

    OrgUnitContextAdvice advice = advice();
    assertNull(advice.activeSquadronId(request));
    assertTrue(advice.isAllMyOrgUnitsMode(request));
    assertFalse(advice.isAllSquadronsMode(request));
    assertTrue(advice.contextSpansOrgUnits(false, true));
    assertEquals("ALL_MY_ORG_UNITS", advice.activeOrgUnitContextKey(request));
  }

  @Test
  void activeSquadronId_ignoresASessionPinTheBackendDidNotHonour() {
    stubLayout(LayoutResponses.context(ContextKind.ALL_MY_ORG_UNITS, null, List.of()));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request
        .getSession(true)
        .setAttribute(
            ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY, UUID.randomUUID().toString());

    assertNull(advice().activeSquadronId(request));
  }

  @Test
  void anAdminWithoutPinIsInAllOrgUnitsMode() {
    stubLayout(LayoutResponses.context(ContextKind.ALL_ORG_UNITS, null, List.of()));
    MockHttpServletRequest request = new MockHttpServletRequest();

    OrgUnitContextAdvice advice = advice();
    assertNull(advice.activeSquadronId(request));
    assertTrue(advice.isAllSquadronsMode(request));
    assertFalse(advice.isAllMyOrgUnitsMode(request));
    assertEquals("ALL_ORG_UNITS", advice.activeOrgUnitContextKey(request));
  }

  @Test
  void aOneUnitContextIsAnnouncedWithItsUnit() {
    UUID unit = UUID.randomUUID();
    stubLayout(LayoutResponses.context(ContextKind.ORG_UNIT, unit, List.of()));
    MockHttpServletRequest request = new MockHttpServletRequest();

    OrgUnitContextAdvice advice = advice();
    assertEquals("ORG_UNIT:" + unit, advice.activeOrgUnitContextKey(request));
    assertFalse(advice.contextSpansOrgUnits(false, false));
  }

  @Test
  void activeSquadronId_backendFailure_degradesToNullAndAnnouncesNothing() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenThrow(new RuntimeException("boom"));
    MockHttpServletRequest request = new MockHttpServletRequest();

    OrgUnitContextAdvice advice = advice();
    assertNull(advice.activeSquadronId(request));
    assertFalse(advice.isAllMyOrgUnitsMode(request));
    assertNull(advice.activeOrgUnitContextKey(request));
  }

  @Test
  void anAnonymousCallerHasNoContextAndCostsNoRead() {
    when(authHelper.isAuthenticated()).thenReturn(false);
    MockHttpServletRequest request = new MockHttpServletRequest();

    OrgUnitContextAdvice advice = advice();
    assertNull(advice.activeSquadronId(request));
    assertFalse(advice.isAllSquadronsMode(request));
    assertNull(advice.activeOrgUnitContextKey(request));
    verify(backendApiClient, never()).get(any(String.class), anyClass());
  }

  private void stubLayout(LayoutContextLoader.MeLayoutResponse response) {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(response);
  }
}
