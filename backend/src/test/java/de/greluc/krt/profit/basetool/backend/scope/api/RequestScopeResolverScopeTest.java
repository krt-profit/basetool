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

package de.greluc.krt.profit.basetool.backend.scope.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

/**
 * Unit tests for {@link RequestScopeResolver#currentScopePredicate()} on the caller's identity
 * (REQ-SEC-052, ADR-0159): a scope question asked by a caller with no identity has no honest
 * answer, so the resolver refuses it instead of inventing one.
 */
@ExtendWith(MockitoExtension.class)
class RequestScopeResolverScopeTest {

  @Mock private AuthHelperService authHelper;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private OrgUnitCascadeService orgUnitCascadeService;
  @Mock private HttpServletRequest request;

  private RequestScopeResolver resolver;

  @BeforeEach
  void setUp() {
    resolver =
        new RequestScopeResolver(
            authHelper,
            orgUnitMembershipRepository,
            orgUnitRepository,
            orgUnitCascadeService,
            request);
  }

  @Test
  void currentScopePredicate_unauthenticatedCaller_throwsRatherThanScopingToNothing() {
    when(authHelper.isAuthenticated()).thenReturn(false);

    AuthenticationCredentialsNotFoundException thrown =
        assertThrows(
            AuthenticationCredentialsNotFoundException.class, resolver::currentScopePredicate);

    assertTrue(thrown.getMessage().contains("REQ-SEC-052"));
  }

  @Test
  void currentScopePredicate_authenticatedCaller_stillAnswers() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(true);
    when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

    assertTrue(resolver.currentScopePredicate().adminAllScope());
  }
}
