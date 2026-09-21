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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;

/** Outbound response payload for the Page operation. */
public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages, List<String> sort) {

  /**
   * Wraps a Spring Data {@link Page} into the outbound envelope, rendering each active sort order
   * as a {@code field,asc|desc} token with a lowercase direction so the client receives the same
   * syntax {@code PaginationUtil.createPageRequest} accepts on the next request. Replaces the
   * per-controller {@code toPageResponse} helpers that were copy-pasted across the controller layer
   * (two of which rendered the direction in uppercase; this factory unifies them on lowercase).
   *
   * @param page the Spring Data page to wrap, must not be {@code null}
   * @param <T> the element type of the page content
   * @return an envelope carrying the page's content, paging metadata and lowercase sort tokens
   */
  @NotNull
  public static <T> PageResponse<T> of(@NotNull Page<T> page) {
    List<String> sort =
        page.getSort().stream()
            .map(
                order ->
                    order.getProperty()
                        + ","
                        + order.getDirection().name().toLowerCase(Locale.ROOT))
            .toList();
    return new PageResponse<>(
        page.getContent(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages(),
        sort);
  }
}
