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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link LayoutMiscAdvice}'s {@code appTitle} composition (REQ-ORG-024): Staffel
 * pin, SK pin, pin without shorthand, admin in all-OrgUnits mode, and no context.
 */
@ExtendWith(MockitoExtension.class)
class LayoutMiscAdviceTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private MessageSource messageSource;
  @Mock private FrontendAuthHelperService authHelper;

  private LayoutMiscAdvice advice() {
    return new LayoutMiscAdvice(
        new LayoutContextLoader(backendApiClient, authHelper), messageSource);
  }

  @Test
  void appTitle_squadronPin_appendsShorthand() {
    stubEchoMessages();
    OrgUnitMembershipOptionDto pin =
        new OrgUnitMembershipOptionDto(UUID.randomUUID(), "IRIDIUM", "IRI", "SQUADRON", true);

    assertEquals("app.title.with.squadron:IRI", advice().appTitle(pin, false));
  }

  @Test
  void appTitle_specialCommandPin_appendsShorthand() {
    stubEchoMessages();
    OrgUnitMembershipOptionDto pin =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Spezialkommando Alpha", "SK-A", "SPECIAL_COMMAND", true);

    assertEquals("app.title.with.squadron:SK-A", advice().appTitle(pin, false));
  }

  @Test
  void appTitle_pinWithoutShorthand_fallsBackToName() {
    stubEchoMessages();
    OrgUnitMembershipOptionDto pin =
        new OrgUnitMembershipOptionDto(UUID.randomUUID(), "Leadership", null, "SQUADRON", true);

    assertEquals("app.title.with.squadron:Leadership", advice().appTitle(pin, false));
  }

  @Test
  void appTitle_adminAllOrgUnitsMode_usesAllLabel() {
    stubEchoMessages();

    assertEquals("app.title.all.squadrons:squadron.switcher.all", advice().appTitle(null, true));
  }

  @Test
  void appTitle_noContext_isPlain() {
    stubEchoMessages();

    assertEquals("app.title", advice().appTitle(null, false));
  }

  @Test
  void unreadNotificationCount_readsTheLayoutAnswer() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(
            new LayoutContextLoader.MeLayoutResponse(
                null, List.of(), CapabilityFlagsAdvice.CapabilitiesResponse.NONE, 7L));

    assertEquals(7L, advice().unreadNotificationCount(new MockHttpServletRequest()));
    verify(backendApiClient, never())
        .get(eq("/api/v1/notifications/unread-count"), ResponseTypeMatchers.anyClass());
  }

  @Test
  void unreadNotificationCount_backendFails_hidesTheBadge() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenThrow(new RuntimeException("boom"));

    assertEquals(0L, advice().unreadNotificationCount(new MockHttpServletRequest()));
  }

  @Test
  void unreadNotificationCount_anonymous_isZeroWithoutACall() {
    when(authHelper.isAuthenticated()).thenReturn(false);

    assertEquals(0L, advice().unreadNotificationCount(new MockHttpServletRequest()));
    verifyNoInteractions(backendApiClient);
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
