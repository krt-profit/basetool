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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The one seam every identity consumer asks (ADR-0129).
 *
 * <p>It exists because the acting-member identity swap introduced a second authentication type and
 * every consumer that branched on the type split into fail-closed and fail-open. What is pinned
 * here is therefore not "it returns the sub" but the two boundaries that are easy to get wrong in
 * opposite directions: it must accept an authentication that carries a subject without a token, and
 * it must refuse to invent one from an authentication that has none.
 */
class AuthenticatedSubjectTest {

  private static final String SUB = "44444444-4444-4444-4444-444444444444";

  /** Stands in for the acting-member authentication: a subject, no token. */
  private static final class TokenlessSubject extends AbstractAuthenticationToken
      implements SubjectAuthentication {

    private final String subject;

    TokenlessSubject(String subject) {
      super(List.of());
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
    public @NotNull String subject() {
      return subject;
    }
  }

  private static Jwt jwt(String subject) {
    Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").claim("scope", "read");
    if (subject != null) {
      builder.subject(subject);
    }
    return builder.build();
  }

  /** The ordinary case: a bearer token's {@code sub}. */
  @Test
  void readsTheSubjectOfABearerToken() {
    assertThat(AuthenticatedSubject.of(new JwtAuthenticationToken(jwt(SUB), List.of())))
        .contains(SUB);
  }

  /**
   * A token-less authentication that advertises a subject is accepted.
   *
   * <p>The half that failed closed: {@code CurrentUserArgumentResolver} demanded a {@code
   * JwtAuthenticationToken} and 403'd every ingest-gateway call at argument resolution.
   */
  @Test
  void readsTheSubjectOfATokenlessAuthenticationThatAdvertisesOne() {
    assertThat(AuthenticatedSubject.of(new TokenlessSubject(SUB))).contains(SUB);
  }

  /**
   * A username/password authentication yields nothing — its name is a callsign.
   *
   * <p>The most important case in this class. A {@code getName()} fallback is the obvious way to
   * make the token-less case above work, and it would have written members' callsigns into the
   * {@code userId} MDC field of every log line for such a caller, which REQ-OBS-004 forbids
   * outright. The opt-in interface exists precisely so this returns empty.
   */
  @Test
  void refusesToReadANameThatIsACallsign() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("Redshift", "secret", List.of());

    assertThat(AuthenticatedSubject.of(auth)).isEmpty();
  }

  /** An anonymous caller is not a subject, even though the token reports itself authenticated. */
  @Test
  void treatsAnAnonymousCallerAsNoSubject() {
    AnonymousAuthenticationToken anonymous =
        new AnonymousAuthenticationToken(
            "key", "anon", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

    assertThat(AuthenticatedSubject.of(anonymous)).isEmpty();
  }

  /** No authentication at all. */
  @Test
  void yieldsNothingForNoAuthentication() {
    assertThat(AuthenticatedSubject.of(null)).isEmpty();
  }

  /** A token without a {@code sub} claim is fail-closed, not blank-valued. */
  @Test
  void yieldsNothingForATokenWithoutASubjectClaim() {
    assertThat(AuthenticatedSubject.of(new JwtAuthenticationToken(jwt(null), List.of()))).isEmpty();
  }

  /** A blank subject is treated as absent rather than as an identity. */
  @Test
  void yieldsNothingForABlankSubject() {
    assertThat(AuthenticatedSubject.of(new TokenlessSubject("   "))).isEmpty();
  }

  /** {@code idOf} parses the subject as a member id. */
  @Test
  void parsesTheSubjectAsAMemberId() {
    assertThat(AuthenticatedSubject.idOf(new TokenlessSubject(SUB))).contains(UUID.fromString(SUB));
  }

  /**
   * A subject that is not a UUID yields no member id.
   *
   * <p>Service accounts and machine callers have UUID subjects here too, but a differently
   * configured realm need not — and the consent gate treats "not a member id" as "not a person who
   * can accept anything", which only works if this returns empty rather than throwing.
   */
  @Test
  void yieldsNoMemberIdForASubjectThatIsNotAUuid() {
    assertThat(AuthenticatedSubject.idOf(new TokenlessSubject("service-account-gateway")))
        .isEmpty();
  }
}
