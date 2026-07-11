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

package de.greluc.krt.profit.basetool.backend.interceptor;

import de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC interceptor that surfaces deprecation metadata as standard HTTP headers.
 *
 * <p>For any handler annotated with {@link ApiDeprecation} or the JDK {@code @Deprecated}
 * annotation (on the method or its declaring controller class), it emits a {@code Deprecation:
 * true} response header. If the {@code @ApiDeprecation} carries a {@code sunset} date in {@code
 * YYYY-MM-DD} form it is reformatted to RFC&nbsp;7231 HTTP-date form and emitted as a {@code
 * Sunset} header; a non-empty {@code replacement} value is emitted as a {@code Link: &lt;...&gt;;
 * rel="alternate"} header. The interceptor never blocks a request — invalid sunset values are
 * logged and skipped.
 */
@Slf4j
@Component
public class DeprecationInterceptor implements HandlerInterceptor {

  private static final DateTimeFormatter HTTP_DATE_FORMATTER =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
          .withZone(ZoneOffset.UTC);

  /**
   * Handler methods whose {@code @ApiDeprecation.sunset} value failed to parse, so the
   * malformed-date WARN is emitted at most once per handler instead of on every request to that
   * endpoint. {@code sunset()} is a compile-time constant, so a bad value would otherwise re-warn
   * on every call.
   */
  private final Set<Method> warnedBadSunset = ConcurrentHashMap.newKeySet();

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod handlerMethod) {
      ApiDeprecation deprecation = handlerMethod.getMethodAnnotation(ApiDeprecation.class);
      boolean isDeprecated = handlerMethod.hasMethodAnnotation(Deprecated.class);

      if (deprecation == null) {
        deprecation = handlerMethod.getBeanType().getAnnotation(ApiDeprecation.class);
      }
      if (!isDeprecated) {
        isDeprecated = handlerMethod.getBeanType().isAnnotationPresent(Deprecated.class);
      }

      if (isDeprecated || deprecation != null) {
        response.addHeader("Deprecation", "true");

        if (deprecation != null) {
          if (!deprecation.sunset().isEmpty()) {
            try {
              LocalDate sunsetDate = LocalDate.parse(deprecation.sunset());
              response.addHeader("Sunset", HTTP_DATE_FORMATTER.format(sunsetDate.atStartOfDay()));
            } catch (DateTimeParseException e) {
              // Warn at most once per handler: sunset() is a compile-time constant, so a malformed
              // value would otherwise WARN on every request to this still-live deprecated endpoint.
              if (warnedBadSunset.add(handlerMethod.getMethod())) {
                log.warn(
                    "Invalid sunset date format on {}: {}. Expected YYYY-MM-DD",
                    handlerMethod.getMethod().getName(),
                    deprecation.sunset());
              }
            }
          }

          if (!deprecation.replacement().isEmpty()) {
            response.addHeader("Link", "<" + deprecation.replacement() + ">; rel=\"alternate\"");
          }
        }
      }
    }
    return true;
  }
}
