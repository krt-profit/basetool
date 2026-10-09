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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Records the calling installation as seen after every exchange request its gate admitted
 * (REQ-XCH-007); a refused request records nothing, and a failed write never fails the request.
 * Registers itself for the exchange routes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeInstallationInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  private final ExchangeInstallationService installationService;

  /**
   * Registers this interceptor for the exchange routes only.
   *
   * @param registry the registry
   */
  @Override
  public void addInterceptors(@NotNull InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/api/v1/exchange/**");
  }

  @Override
  public void postHandle(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull Object handler,
      @Nullable ModelAndView modelAndView) {
    if (!(request.getUserPrincipal() instanceof SubjectAuthentication subject)
        || subject.externalClient() == null
        || subject.exchangeInstallationKey() == null) {
      return;
    }
    try {
      installationService.touch(
          subject.externalClient(),
          UUID.fromString(subject.subject()),
          subject.exchangeInstallationKey());
    } catch (RuntimeException e) {
      log.warn("Could not record an exchange installation as seen: {}", e.getClass().getName());
    }
  }
}
