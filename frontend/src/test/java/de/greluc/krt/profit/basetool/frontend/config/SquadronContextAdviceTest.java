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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.config.SquadronContextAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

/**
 * Unit tests for {@link SquadronContextAdvice}. Two groups: (1) the per-principal capability gates
 * — the shared {@code meCapabilities} resolver (a single backend round-trip, admins
 * short-circuited, anonymous short-circuited, fail-closed on error) and the two derived sidebar
 * flags {@code canSeeBlueprintOverview} (#364) and {@code canViewJobOrders} (profit-eligible order
 * visibility); (2) the {@code appTitle} composition (REQ-ORG-010) — the single surface for the
 * active OrgUnit context after the redundant top-right chip was removed, including the SK-pin case
 * the chip used to be the only surface for.
 */
@ExtendWith(MockitoExtension.class)
class SquadronContextAdviceTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private MessageSource messageSource;
  @Mock private FrontendAuthHelperService authHelper;

  private SquadronContextAdvice advice() {
    return new SquadronContextAdvice(backendApiClient, messageSource, authHelper);
  }

  @Test
  void meCapabilities_anonymous_allFalse_withoutBackendCall() {
    when(authHelper.isAuthenticated()).thenReturn(false);

    CapabilitiesResponse caps = advice().meCapabilities();

    assertFalse(caps.canSeeBlueprintOverview());
    assertFalse(caps.canViewJobOrders());
    verify(backendApiClient, never()).get(any(String.class), anyClass());
  }

  @Test
  void meCapabilities_admin_allTrue_withoutBackendCall() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(true);

    CapabilitiesResponse caps = advice().meCapabilities();

    assertTrue(caps.canSeeBlueprintOverview());
    assertTrue(caps.canViewJobOrders());
    verify(backendApiClient, never()).get(any(String.class), anyClass());
  }

  @Test
  void meCapabilities_nonAdmin_reflectsBackend() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get("/api/v1/me/capabilities", CapabilitiesResponse.class))
        .thenReturn(new CapabilitiesResponse(true, false));

    CapabilitiesResponse caps = advice().meCapabilities();

    assertTrue(caps.canSeeBlueprintOverview());
    assertFalse(caps.canViewJobOrders());
  }

  @Test
  void meCapabilities_nonAdmin_backendFails_failsClosed() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get("/api/v1/me/capabilities", CapabilitiesResponse.class))
        .thenThrow(new RuntimeException("boom"));

    CapabilitiesResponse caps = advice().meCapabilities();

    assertFalse(caps.canSeeBlueprintOverview());
    assertFalse(caps.canViewJobOrders());
  }

  @Test
  void derivedFlags_readFromCapabilities() {
    assertTrue(advice().canSeeBlueprintOverview(new CapabilitiesResponse(true, false)));
    assertFalse(advice().canSeeBlueprintOverview(new CapabilitiesResponse(false, true)));
    assertTrue(advice().canViewJobOrders(new CapabilitiesResponse(false, true)));
    assertFalse(advice().canViewJobOrders(new CapabilitiesResponse(true, false)));
  }

  @Test
  void derivedFlags_nullCapabilities_areFalse() {
    assertFalse(advice().canSeeBlueprintOverview(null));
    assertFalse(advice().canViewJobOrders(null));
  }

  @Test
  void availableSquadrons_routesSquadronCatalogueThroughCache() {
    // REQ-DATA-007: the squadron catalogue is a slow-changing global list fetched on every
    // authenticated render; it must go through the 10-min STATIC_DATA_CACHE (getCached), not a
    // per-render plain GET. getCached is unstubbed (returns null → advice degrades to empty); the
    // assertion is about the routing, not the payload.
    when(authHelper.isAuthenticated()).thenReturn(true);

    advice().availableSquadrons();

    verify(backendApiClient)
        .getCached(eq("/api/v1/squadrons?size=1000&sort=name,asc"), anyTypeRef());
    verify(backendApiClient, never())
        .get(eq("/api/v1/squadrons?size=1000&sort=name,asc"), anyTypeRef());
  }

  @Test
  void adminSwitcher_routesSquadronCatalogueThroughCache_notPlainGet() {
    // REQ-DATA-007: the admin switcher's squadron catalogue goes through getCached (the same
    // URI-keyed STATIC_DATA_CACHE entry availableSquadrons() uses), never a plain GET. This pins
    // the routing precondition for the cross-call de-dup; the actual single-fetch is the shared
    // URI-keyed cache (Caffeine), not exercised here since backendApiClient is mocked.
    // Special-commands stays a plain GET (admin-only, intentionally uncached — see REQ-DATA-007).
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(true);

    advice().availableOrgUnits();

    verify(backendApiClient)
        .getCached(eq("/api/v1/squadrons?size=1000&sort=name,asc"), anyTypeRef());
    verify(backendApiClient, never())
        .get(eq("/api/v1/squadrons?size=1000&sort=name,asc"), anyTypeRef());
  }

  @Test
  void appTitle_squadronPin_appendsShorthand() {
    // covers REQ-ORG-010 — a Staffel pin surfaces in the app title.
    stubEchoMessages();
    OrgUnitMembershipOptionDto pin =
        new OrgUnitMembershipOptionDto(UUID.randomUUID(), "IRIDIUM", "IRI", "SQUADRON", true);

    assertEquals("app.title.with.squadron:IRI", advice().appTitle(pin, false));
  }

  @Test
  void appTitle_specialCommandPin_appendsShorthand() {
    // covers REQ-ORG-010 — an SK pin now surfaces in the app title. The removed context chip used
    // to be the only surface that showed an SK pin (appTitle previously read the Squadron-only
    // catalogue); this guards against that regression.
    stubEchoMessages();
    OrgUnitMembershipOptionDto pin =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Spezialkommando Alpha", "SK-A", "SPECIAL_COMMAND", true);

    assertEquals("app.title.with.squadron:SK-A", advice().appTitle(pin, false));
  }

  @Test
  void appTitle_pinWithoutShorthand_fallsBackToName() {
    // covers REQ-ORG-010 — shorthand is optional; the OrgUnit name is the fallback suffix.
    stubEchoMessages();
    OrgUnitMembershipOptionDto pin =
        new OrgUnitMembershipOptionDto(UUID.randomUUID(), "Leadership", null, "SQUADRON", true);

    assertEquals("app.title.with.squadron:Leadership", advice().appTitle(pin, false));
  }

  @Test
  void appTitle_adminAllOrgUnitsMode_usesAllLabel() {
    // covers REQ-ORG-010 — admin without a pin shows the localised "all squadrons" label.
    stubEchoMessages();

    assertEquals("app.title.all.squadrons:squadron.switcher.all", advice().appTitle(null, true));
  }

  @Test
  void appTitle_noContext_isPlain() {
    // covers REQ-ORG-010 — no active context renders the plain product title.
    stubEchoMessages();

    assertEquals("app.title", advice().appTitle(null, false));
  }

  /**
   * Stubs the mocked {@link MessageSource} to echo each requested code, suffixed with the first
   * format argument when one is present (e.g. {@code "app.title.with.squadron:IRI"}). Keeps the
   * {@code appTitle} assertions focused on the resolved code + argument without binding to real
   * bundle text or fiddly array matchers.
   */
  private void stubEchoMessages() {
    when(messageSource.getMessage(any(String.class), any(), any(Locale.class)))
        .thenAnswer(
            inv -> {
              String code = inv.getArgument(0);
              Object[] args = inv.getArgument(1);
              return args != null && args.length > 0 ? code + ":" + args[0] : code;
            });
  }
}
