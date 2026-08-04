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

import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import java.security.Principal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentUserSub}-annotated {@link String} and {@link CurrentUserId}-annotated
 * {@link UUID} controller parameters from the authenticated caller's JWT {@code sub} claim.
 *
 * <p>This is the single implementation of the {@code requireSub(JwtAuthenticationToken)} guard that
 * six controllers previously hand-rolled (five returning the raw subject, {@code
 * NotificationController} returning a parsed UUID). The principal is read via {@link
 * NativeWebRequest#getUserPrincipal()} — the exact source Spring MVC used to populate the {@code
 * JwtAuthenticationToken} method parameters these annotations replace — so no {@link
 * org.springframework.security.core.context.SecurityContextHolder} coupling is introduced.
 *
 * <p>Failure semantics are preserved verbatim: a missing/non-JWT principal, a missing or blank
 * subject, or (for {@link CurrentUserId}) a non-UUID subject each raise {@link
 * AccessDeniedException} with the same messages as before, which the security layer renders as RFC
 * 7807 {@code 403}.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

  /**
   * Claims {@link String} parameters annotated with {@link CurrentUserSub} and {@link UUID}
   * parameters annotated with {@link CurrentUserId}; the type check guards against the annotation
   * being placed on a parameter of the wrong type.
   *
   * @param parameter the candidate controller parameter
   * @return {@code true} for a correctly-typed {@code @CurrentUserSub}/{@code @CurrentUserId}
   *     parameter
   */
  @Override
  public boolean supportsParameter(@NotNull MethodParameter parameter) {
    return (parameter.hasParameterAnnotation(CurrentUserSub.class)
            && String.class.equals(parameter.getParameterType()))
        || (parameter.hasParameterAnnotation(CurrentUserId.class)
            && UUID.class.equals(parameter.getParameterType()));
  }

  /**
   * Returns the caller's subject as a {@link String} for {@link CurrentUserSub} parameters, or as a
   * {@link UUID} for {@link CurrentUserId} parameters.
   *
   * @param parameter the parameter being resolved (drives the String-vs-UUID return type)
   * @param mavContainer unused MVC container
   * @param webRequest the current request, source of the authenticated principal
   * @param binderFactory unused data-binder factory
   * @return the subject String or its parsed UUID
   * @throws AccessDeniedException if the JWT/subject is missing or, for a UUID target, malformed
   */
  @Override
  @NotNull
  public Object resolveArgument(
      @NotNull MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      @NotNull NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    String sub = requireSubject(webRequest);
    if (parameter.hasParameterAnnotation(CurrentUserId.class)) {
      try {
        return UUID.fromString(sub);
      } catch (IllegalArgumentException ex) {
        throw new AccessDeniedException("JWT subject claim is not a valid identifier.");
      }
    }
    return sub;
  }

  /**
   * Extracts and validates the JWT subject from the current request, applying the null-JWT and
   * blank-subject guards the controllers shared.
   *
   * @param webRequest the current request
   * @return the non-blank subject claim
   * @throws AccessDeniedException if no JWT principal is bound or the subject is missing/blank
   */
  @NotNull
  private static String requireSubject(@NotNull NativeWebRequest webRequest) {
    // Asked of AuthenticatedSubject, not of the type. A request the ingest gateway makes on behalf
    // of a member carries that member's identity with NO token behind it (ADR-0129), so demanding a
    // JwtAuthenticationToken here threw before the handler body ran — every gateway call 403'd at
    // argument resolution, one layer past the gate that used to fail it.
    Principal principal = webRequest.getUserPrincipal();
    return AuthenticatedSubject.of(principal instanceof Authentication auth ? auth : null)
        .orElseThrow(() -> new AccessDeniedException("No authenticated subject."));
  }
}
