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

package de.greluc.krt.profit.basetool.frontend.kernel.model;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Outbound response payload for the Page operation. */
@DtoMirror
public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages, List<String> sort) {

  /**
   * Returns the rows of a page that may be absent.
   *
   * @param page the page, or {@code null} when the backend answered nothing
   * @param <T> the row type
   * @return the rows, empty for an absent page or an absent row list
   */
  @NotNull
  public static <T> List<T> contentOf(@Nullable PageResponse<T> page) {
    return page == null || page.content() == null ? List.of() : page.content();
  }

  /**
   * Returns the total row count of a page that may be absent.
   *
   * @param page the page, or {@code null} when the backend answered nothing
   * @return the total, {@code 0} for an absent page
   */
  public static long totalOf(@Nullable PageResponse<?> page) {
    return page == null ? 0L : page.totalElements();
  }
}
