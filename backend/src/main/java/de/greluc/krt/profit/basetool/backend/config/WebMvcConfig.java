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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.interceptor.DeprecationInterceptor;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserArgumentResolver;
import de.greluc.krt.profit.basetool.backend.web.UserZoneArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link DeprecationInterceptor}, which emits the {@code Deprecation}/{@code
 * Sunset}/{@code Link} headers on deprecated endpoints, and the {@link CurrentUserArgumentResolver}
 * and {@link UserZoneArgumentResolver} behind {@code @CurrentUserId} and {@code @UserZone}.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

  private final DeprecationInterceptor deprecationInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(deprecationInterceptor);
  }

  /**
   * Registers the JWT-subject and user-time-zone argument resolvers so their annotations resolve on
   * every controller. The resolvers are stateless, so a fresh instance per registration is fine.
   *
   * @param resolvers the mutable resolver list Spring MVC assembles for controller dispatch
   */
  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CurrentUserArgumentResolver());
    resolvers.add(new UserZoneArgumentResolver());
  }
}
