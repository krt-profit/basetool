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

package de.greluc.krt.profit.basetool.frontend.config;

import java.io.Serial;
import java.net.URI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * Raised by {@link BackendOriginGuard} when a backend {@code WebClient} is asked to send a request
 * to any origin other than the configured backend's; the request was not sent (REQ-FE-029).
 *
 * <p>The message names the method and the refused origin only, never the path or query.
 */
public class BackendOriginViolationException extends IllegalStateException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception for one refused request.
   *
   * @param method the request method
   * @param url the refused request URL
   */
  public BackendOriginViolationException(@NotNull HttpMethod method, @Nullable URI url) {
    super("Refused " + method.name() + " to " + origin(url) + ": not the configured backend");
  }

  private static String origin(@Nullable URI url) {
    if (url == null || url.getHost() == null) {
      return "a URL without a host";
    }
    return url.getScheme()
        + "://"
        + url.getHost()
        + (url.getPort() >= 0 ? ":" + url.getPort() : "");
  }
}
