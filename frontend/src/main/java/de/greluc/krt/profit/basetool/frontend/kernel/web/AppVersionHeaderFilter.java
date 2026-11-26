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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.AppVersion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sends the running version ({@link AppVersion}) with every answer, page, fragment, JSON and static
 * file alike, so an open page that was rendered by another version offers a reload (REQ-FE-042).
 */
@Component
@RequiredArgsConstructor
public class AppVersionHeaderFilter extends OncePerRequestFilter {

  /** The response header naming the running version; {@code krt-app-version.js} reads it. */
  static final String HEADER = "X-Basetool-Version";

  private final Optional<BuildProperties> buildProperties;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader(HEADER, AppVersion.of(buildProperties.orElse(null)));
    filterChain.doFilter(request, response);
  }
}
