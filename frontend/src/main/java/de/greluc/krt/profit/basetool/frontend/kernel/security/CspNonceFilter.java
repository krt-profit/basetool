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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Generates a per-request CSP nonce and exposes it via the request attribute {@code cspNonce}.
 * Thymeleaf templates reference it as {@code ${cspNonce}} on every {@code <script>} tag; the same
 * attribute is read by the CSP header writer in {@link SecurityConfig} to emit {@code script-src
 * 'nonce-XYZ' 'strict-dynamic'}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CspNonceFilter extends OncePerRequestFilter {

  public static final String REQUEST_ATTRIBUTE = "cspNonce";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int NONCE_BYTES = 16;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    byte[] bytes = new byte[NONCE_BYTES];
    RANDOM.nextBytes(bytes);
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    request.setAttribute(REQUEST_ATTRIBUTE, nonce);
    chain.doFilter(request, response);
  }
}
