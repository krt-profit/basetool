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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** The request-header conditions that tell a {@code krtFetch} call from a page navigation. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RequestHeaders {

  /** Matches a {@code krtFetch} call, which always sends {@code X-Requested-With}. */
  public static final String AJAX = "X-Requested-With=XMLHttpRequest";

  /** Matches a page navigation, which never sends {@code X-Requested-With}. */
  public static final String PAGE_ONLY = "!X-Requested-With";
}
