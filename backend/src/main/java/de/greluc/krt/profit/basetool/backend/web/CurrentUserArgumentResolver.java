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
 * Resolves {@link CurrentUserId}-annotated {@link UUID} controller parameters from the
 * authenticated caller's subject.
 *
 * <p>The subject is read via {@link
 * de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject}, so a subject without a JWT,
 * as on ingest-gateway calls (ADR-0129), resolves too. An absent or non-UUID subject raises {@link
 * AccessDeniedException} (403).
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

  /**
   * Claims {@link UUID} parameters annotated with {@link CurrentUserId}; the type check guards
   * against the annotation being placed on a parameter of the wrong type.
   *
   * @param parameter the candidate controller parameter
   * @return {@code true} for a correctly-typed {@code @CurrentUserId} parameter
   */
  @Override
  public boolean supportsParameter(@NotNull MethodParameter parameter) {
    return parameter.hasParameterAnnotation(CurrentUserId.class)
        && UUID.class.equals(parameter.getParameterType());
  }

  /**
   * Returns the caller's subject parsed into the {@link UUID} that is their {@code app_user.id}.
   *
   * @param parameter the parameter being resolved
   * @param mavContainer unused MVC container
   * @param webRequest the current request, source of the authenticated principal
   * @param binderFactory unused data-binder factory
   * @return the caller's user id
   * @throws AccessDeniedException if the JWT/subject is missing or malformed
   */
  @Override
  @NotNull
  public Object resolveArgument(
      @NotNull MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      @NotNull NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    String sub = requireSubject(webRequest);
    try {
      return UUID.fromString(sub);
    } catch (IllegalArgumentException ex) {
      throw new AccessDeniedException("JWT subject claim is not a valid identifier.");
    }
  }

  /**
   * Extracts the caller's subject from the current request and rejects a missing or blank one.
   *
   * @param webRequest the current request
   * @return the non-blank subject claim
   * @throws AccessDeniedException if no JWT principal is bound or the subject is missing/blank
   */
  @NotNull
  private static String requireSubject(@NotNull NativeWebRequest webRequest) {
    Principal principal = webRequest.getUserPrincipal();
    return AuthenticatedSubject.of(principal instanceof Authentication auth ? auth : null)
        .orElseThrow(() -> new AccessDeniedException("No authenticated subject."));
  }
}
