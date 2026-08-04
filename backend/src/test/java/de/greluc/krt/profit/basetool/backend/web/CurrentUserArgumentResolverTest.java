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

package de.greluc.krt.profit.basetool.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Unit tests for {@link CurrentUserArgumentResolver}, asserting it reproduces the exact {@code
 * requireSub(JwtAuthenticationToken)} guards it replaced: which parameters it claims, the
 * String-vs-UUID return shaping, and the {@link AccessDeniedException} failure modes.
 */
class CurrentUserArgumentResolverTest {

  private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();

  /** Reflection target providing annotated parameters for {@link MethodParameter} construction. */
  @SuppressWarnings("unused")
  private void handlers(
      @CurrentUserSub String sub,
      @CurrentUserId UUID id,
      @CurrentUserSub UUID subOnWrongType,
      String plain) {
    // Parameter carrier only; never invoked.
  }

  private static MethodParameter param(int index) throws NoSuchMethodException {
    Method method =
        CurrentUserArgumentResolverTest.class.getDeclaredMethod(
            "handlers", String.class, UUID.class, UUID.class, String.class);
    return new MethodParameter(method, index);
  }

  private static JwtAuthenticationToken tokenWithSubject(String subject) {
    Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
    if (subject != null) {
      builder.subject(subject);
    } else {
      builder.claim("nosub", "present");
    }
    return new JwtAuthenticationToken(builder.build());
  }

  private static NativeWebRequest requestWithPrincipal(Principal principal) {
    NativeWebRequest request = mock(NativeWebRequest.class);
    when(request.getUserPrincipal()).thenReturn(principal);
    return request;
  }

  @Test
  void supportsCurrentUserSubOnString() throws Exception {
    assertThat(resolver.supportsParameter(param(0))).isTrue();
  }

  @Test
  void supportsCurrentUserIdOnUuid() throws Exception {
    assertThat(resolver.supportsParameter(param(1))).isTrue();
  }

  @Test
  void rejectsCurrentUserSubOnNonStringParameter() throws Exception {
    assertThat(resolver.supportsParameter(param(2))).isFalse();
  }

  @Test
  void rejectsUnannotatedParameter() throws Exception {
    assertThat(resolver.supportsParameter(param(3))).isFalse();
  }

  @Test
  void resolvesSubjectAsRawStringForCurrentUserSub() throws Exception {
    NativeWebRequest request = requestWithPrincipal(tokenWithSubject("subject-123"));
    Object resolved = resolver.resolveArgument(param(0), null, request, null);
    assertThat(resolved).isEqualTo("subject-123");
  }

  @Test
  void resolvesSubjectAsUuidForCurrentUserId() throws Exception {
    UUID expected = UUID.randomUUID();
    NativeWebRequest request = requestWithPrincipal(tokenWithSubject(expected.toString()));
    Object resolved = resolver.resolveArgument(param(1), null, request, null);
    assertThat(resolved).isEqualTo(expected);
  }

  @Test
  void throwsWhenNoPrincipalBound() throws Exception {
    NativeWebRequest request = requestWithPrincipal(null);
    assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("No authenticated subject.");
  }

  /**
   * A principal that is not a token-backed identity is refused — and specifically NOT read for its
   * name.
   *
   * <p>The name here would be the member's callsign, which REQ-OBS-004 keeps out of logs. Since
   * ADR-0129 the resolver asks {@code AuthenticatedSubject}, which only accepts a JWT subject or an
   * authentication that explicitly advertises one; a bare {@code Principal} qualifies as neither.
   */
  @Test
  void throwsWhenPrincipalIsNotAJwt() throws Exception {
    NativeWebRequest request = requestWithPrincipal(() -> "someName");
    assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("No authenticated subject.");
  }

  @Test
  void throwsWhenSubjectMissing() throws Exception {
    NativeWebRequest request = requestWithPrincipal(tokenWithSubject(null));
    assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("No authenticated subject.");
  }

  @Test
  void throwsWhenSubjectBlank() throws Exception {
    NativeWebRequest request = requestWithPrincipal(tokenWithSubject("   "));
    assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("No authenticated subject.");
  }

  @Test
  void throwsWhenSubjectNotAUuidForCurrentUserId() throws Exception {
    NativeWebRequest request = requestWithPrincipal(tokenWithSubject("not-a-uuid"));
    assertThatThrownBy(() -> resolver.resolveArgument(param(1), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("JWT subject claim is not a valid identifier.");
  }
}
