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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Unit tests for {@link DraftScopeAdvice}: the draft scope is a stable, opaque per-member key that
 * never carries the subject, and an anonymous request has none (REQ-FE-041).
 */
class DraftScopeAdviceTest {

  private final DraftScopeAdvice advice = new DraftScopeAdvice();

  @Test
  void draftScope_isStableForOneMemberAndDiffersBetweenMembers() {
    String first = advice.draftScope(member("3f6c1a52-0d1e-4b8a-9f00-1c2d3e4f5a6b"));
    String again = advice.draftScope(member("3f6c1a52-0d1e-4b8a-9f00-1c2d3e4f5a6b"));
    String other = advice.draftScope(member("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"));

    assertNotNull(first);
    assertEquals(first, again);
    assertNotEquals(first, other);
  }

  @Test
  void draftScope_isLowercaseHexOfFixedLengthAndHidesTheSubject() {
    String subject = "3f6c1a52-0d1e-4b8a-9f00-1c2d3e4f5a6b";

    String scope = advice.draftScope(member(subject));

    assertNotNull(scope);
    assertEquals(DraftScopeAdvice.SCOPE_HEX_CHARS, scope.length());
    assertTrue(scope.matches("[0-9a-f]+"));
    assertFalse(subject.replace("-", "").contains(scope));
  }

  @Test
  void draftScope_withoutMemberOrSubject_isNull() {
    assertNull(advice.draftScope(null));
    assertNull(advice.draftScope(member(" ")));
  }

  /**
   * Builds a signed-in member with the given subject.
   *
   * @param subject the identity provider's subject
   * @return the mocked OIDC user
   */
  private static OidcUser member(String subject) {
    OidcUser user = mock(OidcUser.class);
    when(user.getSubject()).thenReturn(subject);
    return user;
  }
}
