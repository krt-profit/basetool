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

import de.greluc.krt.profit.basetool.backend.support.SubjectAuthentication;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Unit tests for {@link CurrentUserArgumentResolver}, asserting it reproduces the exact {@code
 * requireSub(JwtAuthenticationToken)} guards it replaced: which parameters it claims, the parsed
 * UUID it returns, and the {@link AccessDeniedException} failure modes.
 */
class CurrentUserArgumentResolverTest {

  private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();

  /** {@code @CurrentUserId} on a {@link UUID} — the one shape the resolver claims. */
  private static final int ANNOTATED_UUID = 0;

  /** {@code @CurrentUserId} on a {@link String} — right annotation, wrong type. */
  private static final int ANNOTATED_WRONG_TYPE = 1;

  /** No annotation at all. */
  private static final int UNANNOTATED = 2;

  /**
   * Reflection target supplying annotated {@link MethodParameter}s; it is never invoked and throws
   * if it is.
   *
   * @param id the shape the resolver claims
   * @param idOnWrongType the right annotation on the wrong type, which it must not claim
   * @param plain an unannotated parameter, which it must not claim either
   */
  @SuppressWarnings("unused")
  private void handlers(@CurrentUserId UUID id, @CurrentUserId String idOnWrongType, String plain) {
    throw new UnsupportedOperationException(
        "Reflection carrier for MethodParameter; never invoked: " + id + idOnWrongType + plain);
  }

  private static MethodParameter param(int index) throws NoSuchMethodException {
    Method method =
        CurrentUserArgumentResolverTest.class.getDeclaredMethod(
            "handlers", UUID.class, String.class, String.class);
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

  /**
   * An authentication that carries a subject with no token behind it, as installed by the ingest
   * gateway's identity swap (ADR-0129).
   */
  private static final class TokenlessSubject extends AbstractAuthenticationToken
      implements SubjectAuthentication {

    private final String subject;

    TokenlessSubject(String subject) {
      super(java.util.List.of());
      this.subject = subject;
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return subject;
    }

    @Override
    public @org.jetbrains.annotations.NotNull String subject() {
      return subject;
    }
  }

  private static NativeWebRequest requestWithPrincipal(Principal principal) {
    NativeWebRequest request = mock(NativeWebRequest.class);
    when(request.getUserPrincipal()).thenReturn(principal);
    return request;
  }

  @Test
  void supportsCurrentUserIdOnUuid() throws Exception {
    assertThat(resolver.supportsParameter(param(ANNOTATED_UUID))).isTrue();
  }

  @Test
  void rejectsCurrentUserIdOnNonUuidParameter() throws Exception {
    assertThat(resolver.supportsParameter(param(ANNOTATED_WRONG_TYPE))).isFalse();
  }

  @Test
  void rejectsUnannotatedParameter() throws Exception {
    assertThat(resolver.supportsParameter(param(UNANNOTATED))).isFalse();
  }

  @Test
  void resolvesSubjectAsUuidForCurrentUserId() throws Exception {
    UUID expected = UUID.randomUUID();
    NativeWebRequest request = requestWithPrincipal(tokenWithSubject(expected.toString()));
    Object resolved = resolver.resolveArgument(param(ANNOTATED_UUID), null, request, null);
    assertThat(resolved).isEqualTo(expected);
  }

  /** A tokenless subject resolves as the acting member. */
  @Test
  void resolvesATokenlessSubjectForCurrentUserId() throws Exception {
    UUID id = UUID.randomUUID();
    NativeWebRequest request = requestWithPrincipal(new TokenlessSubject(id.toString()));

    assertThat(resolver.resolveArgument(param(ANNOTATED_UUID), null, request, null)).isEqualTo(id);
  }

  @Test
  void throwsWhenNoPrincipalBound() throws Exception {
    NativeWebRequest request = requestWithPrincipal(null);
    assertThatThrownBy(() -> resolver.resolveArgument(param(ANNOTATED_UUID), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("No authenticated subject.");
  }

  /**
   * A principal that is not a token-backed identity is refused without its name being read
   * (REQ-OBS-004).
   */
  @Test
  void throwsWhenPrincipalIsNotAJwt() throws Exception {
    NativeWebRequest request = requestWithPrincipal(() -> "someName");
    assertThatThrownBy(() -> resolver.resolveArgument(param(ANNOTATED_UUID), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("No authenticated subject.");
  }

  @Test
  void throwsWhenSubjectMissing() throws Exception {
    NativeWebRequest request = requestWithPrincipal(tokenWithSubject(null));
    assertThatThrownBy(() -> resolver.resolveArgument(param(ANNOTATED_UUID), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("No authenticated subject.");
  }

  @Test
  void throwsWhenSubjectBlank() throws Exception {
    NativeWebRequest request = requestWithPrincipal(tokenWithSubject("   "));
    assertThatThrownBy(() -> resolver.resolveArgument(param(ANNOTATED_UUID), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("No authenticated subject.");
  }

  @Test
  void throwsWhenSubjectNotAUuidForCurrentUserId() throws Exception {
    NativeWebRequest request = requestWithPrincipal(tokenWithSubject("not-a-uuid"));
    assertThatThrownBy(() -> resolver.resolveArgument(param(ANNOTATED_UUID), null, request, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("JWT subject claim is not a valid identifier.");
  }
}
