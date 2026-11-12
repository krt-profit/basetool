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

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import org.jetbrains.annotations.NotNull;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * A stand-in for the injected proxy of the thread-bound request: like Spring's, it fails with
 * {@link IllegalStateException} on a thread without a bound request, as background work run as a
 * caller has none, and reads an empty request otherwise.
 */
public final class UnboundRequestProxy {

  /** Non-instantiable fixture holder. */
  private UnboundRequestProxy() {}

  /**
   * Creates the proxy.
   *
   * @return a request proxy that is readable only while a request is bound to the thread
   */
  public static @NotNull HttpServletRequest create() {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            UnboundRequestProxy.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (_, method, args) -> {
              if (RequestContextHolder.getRequestAttributes() == null) {
                throw new IllegalStateException("No thread-bound request found");
              }
              return method.invoke(new MockHttpServletRequest(), args);
            });
  }
}
