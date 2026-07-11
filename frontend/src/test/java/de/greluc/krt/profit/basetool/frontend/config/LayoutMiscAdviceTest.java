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
import static org.mockito.Mockito.when;

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
 * Unit tests for {@link LayoutMiscAdvice}. Focuses on the {@code appTitle} composition
 * (REQ-ORG-010) — the single surface for the active OrgUnit context after the redundant top-right
 * chip was removed — across all five branches: a Staffel pin, an SK pin (the case the removed chip
 * used to be the only surface for), a pin without a shorthand (name fallback), an admin in
 * all-OrgUnits mode, and no context at all.
 */
@ExtendWith(MockitoExtension.class)
class LayoutMiscAdviceTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private MessageSource messageSource;
  @Mock private FrontendAuthHelperService authHelper;

  private LayoutMiscAdvice advice() {
    return new LayoutMiscAdvice(backendApiClient, messageSource, authHelper);
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
