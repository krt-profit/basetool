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

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The one place that answers "which subject is this request acting as".
 *
 * <p><strong>Two tempting shortcuts are deliberately not taken.</strong>
 *
 * <ul>
 *   <li><em>Falling back to {@code getName()}.</em> It reads like the general case and is the one
 *       thing that must not happen: on a username/password authentication that name is the member's
 *       callsign, and REQ-OBS-004 keeps callsigns out of log lines and MDC fields because they are
 *       PII. A token-less authentication opts in by implementing {@link SubjectAuthentication}
 *       instead, which is a promise that the value really is a {@code sub}.
 *   <li><em>Gating on {@code isAuthenticated()}.</em> It looks like free fail-closed hardening, but
 *       Spring's single-argument {@code JwtAuthenticationToken(Jwt)} constructor leaves the flag
 *       false, so the check would silently drop identities that are genuinely present. The type
 *       discrimination above already excludes the anonymous token, which is the case the flag was
 *       reached for.
 * </ul>
 *
 * <p>The application grew two idioms for that question — {@code Authentication#getName()}, and
 * {@code instanceof JwtAuthenticationToken} — and they were interchangeable only for as long as
 * every authenticated caller carried a token. ADR-0129 ended that: a request the ingest gateway
 * makes on behalf of a member carries the member's identity with no token behind it.
 *
 * <p>Leaving both idioms in place cost two defects at once, in opposite directions, and they are
 * the reason this class exists rather than a wider {@code instanceof}:
 *
 * <ul>
 *   <li>{@code CurrentUserArgumentResolver} demanded a token and threw — every gateway call failed
 *       at argument resolution, one layer past the gate that used to fail it.
 *   <li>{@code TermsAcceptanceAccessFilter} demanded a token and, finding none, <em>let the request
 *       through</em> — the consent gate (REQ-SEC-028) silently stopped applying to the very path
 *       ADR-0129 was written to keep it applying to.
 * </ul>
 *
 * <p>One failed closed and one failed open, from the same type check. So the check lives here once,
 * and every consumer asks this class instead of asking the type.
 */
public final class AuthenticatedSubject {

  private AuthenticatedSubject() {
    // Utility holder — not instantiable.
  }

  /**
   * Extracts the acting subject, whether it arrived in a token or was established for a member.
   *
   * <p>Reads the token's {@code sub} when there is one, and otherwise only from an authentication
   * that opts in via {@link SubjectAuthentication} — never from {@link Authentication#getName()},
   * which on a username/password token is the member's callsign (REQ-OBS-004). Deliberately
   * tolerant of an absent authentication: callers decide whether "no subject" means refuse or means
   * anonymous, and those two answers differ per call site.
   *
   * @param authentication the current authentication, may be {@code null}
   * @return the subject, or empty when there is no authenticated caller
   */
  public static Optional<String> of(@Nullable Authentication authentication) {
    if (authentication == null) {
      return Optional.empty();
    }
    if (authentication instanceof JwtAuthenticationToken jwtAuth && jwtAuth.getToken() != null) {
      return Optional.ofNullable(jwtAuth.getToken().getSubject()).filter(s -> !s.isBlank());
    }
    if (authentication.getPrincipal() instanceof Jwt jwt) {
      return Optional.ofNullable(jwt.getSubject()).filter(s -> !s.isBlank());
    }
    if (authentication instanceof SubjectAuthentication subjectAuth) {
      // ofNullable, matching the two branches above, although the interface contract is @NotNull:
      // an implementation that breaks that contract should yield "no subject" here rather than a
      // NullPointerException inside a security filter.
      return Optional.ofNullable(subjectAuth.subject()).filter(s -> !s.isBlank());
    }
    // Everything else has no subject — NOT a fallback to getName(). See the class Javadoc: on an
    // AnonymousAuthenticationToken that name is a placeholder, and on a username/password token it
    // is the member's callsign, which REQ-OBS-004 keeps out of logs entirely.
    return Optional.empty();
  }

  /**
   * Same as {@link #of(Authentication)}, as the {@link UUID} the persistence layer wants.
   *
   * <p>Empty rather than throwing for a non-UUID subject: a service account or a malformed token
   * has a subject that is simply not a member id, and whether that is an error depends on the call
   * site.
   *
   * @param authentication the current authentication, may be {@code null}
   * @return the subject as a UUID, or empty when absent or not a UUID
   */
  public static Optional<UUID> idOf(@Nullable Authentication authentication) {
    return of(authentication)
        .flatMap(
            subject -> {
              try {
                return Optional.of(UUID.fromString(subject));
              } catch (IllegalArgumentException notAnId) {
                return Optional.empty();
              }
            });
  }
}
