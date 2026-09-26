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

import java.time.DateTimeException;
import java.time.ZoneId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link UserZone}-annotated {@link ZoneId} parameters from the {@code X-User-Time-Zone}
 * header. The value is trimmed; an absent, blank or invalid zone resolves to {@code null}.
 */
public class UserZoneArgumentResolver implements HandlerMethodArgumentResolver {

  /** The request header carrying the caller's IANA time-zone id (e.g. {@code Europe/Berlin}). */
  static final String HEADER = "X-User-Time-Zone";

  /**
   * Claims {@link ZoneId} parameters annotated with {@link UserZone}.
   *
   * @param parameter the candidate controller parameter
   * @return {@code true} for a {@code ZoneId} parameter carrying {@link UserZone}
   */
  @Override
  public boolean supportsParameter(@NotNull MethodParameter parameter) {
    return parameter.hasParameterAnnotation(UserZone.class)
        && ZoneId.class.equals(parameter.getParameterType());
  }

  /**
   * Reads the {@code X-User-Time-Zone} header and parses it, tolerating absence and malformed
   * values.
   *
   * @param parameter unused resolved parameter
   * @param mavContainer unused MVC container
   * @param webRequest the current request, source of the header
   * @param binderFactory unused data-binder factory
   * @return the parsed {@link ZoneId}, or {@code null} when the header is absent/blank/invalid
   */
  @Override
  @Nullable
  public Object resolveArgument(
      @NotNull MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      @NotNull NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    return parse(webRequest.getHeader(HEADER));
  }

  /**
   * Parses an {@code X-User-Time-Zone} header value into a {@link ZoneId}, silently dropping a
   * {@code null}, blank or unparseable value to {@code null} (the report services then render in
   * UTC).
   *
   * @param raw the raw header value; may be {@code null}
   * @return the parsed zone, or {@code null} when absent/blank/invalid
   */
  @Nullable
  static ZoneId parse(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return ZoneId.of(raw.trim());
    } catch (DateTimeException ex) {
      return null;
    }
  }
}
