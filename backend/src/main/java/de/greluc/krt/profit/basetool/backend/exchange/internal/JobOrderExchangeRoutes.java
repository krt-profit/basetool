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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The job-order route family of the backend's exchange surface (REQ-OXCH-009): every path at or
 * below {@value #PATH}, which the job-order family switch gates, and its {@code POST}s, which the
 * writes switch gates as well. A job-order route only has to live there to be covered; every
 * job-order write is a {@code POST} and every read a {@code GET}.
 */
public final class JobOrderExchangeRoutes {

  /** The root of the job-order route family below the relay prefix. */
  public static final String PATH = "/api/v1/exchange/job-orders";

  /** The job-order route family as a pattern, matched against the decoded path segments. */
  private static final PathPattern FAMILY = PathPatternParser.defaultInstance.parse(PATH + "/**");

  /** Not instantiable. */
  private JobOrderExchangeRoutes() {}

  /**
   * Tells whether a path belongs to the job-order route family, judged on the decoded segments as
   * request mapping matches them, so a percent-encoded spelling is in the family too.
   *
   * @param path a raw request URI, or {@code null}
   * @return {@code true} for {@value #PATH} and every path below it
   */
  @Contract(value = "null -> false", pure = true)
  public static boolean isJobOrder(@Nullable String path) {
    return path != null && FAMILY.matches(PathContainer.parsePath(path));
  }

  /**
   * Tells whether a request is a job-order write: a {@code POST} at or below {@value #PATH}.
   *
   * @param method the request's HTTP method, or {@code null}
   * @param path a raw request URI, or {@code null}
   * @return {@code true} for a {@code POST} in the family
   */
  @Contract(pure = true)
  public static boolean isWrite(@Nullable String method, @Nullable String path) {
    return method != null && HttpMethod.POST.matches(method) && isJobOrder(path);
  }
}
