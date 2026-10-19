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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.ui.Model;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Request normalisation and model wiring of the paginated catalogue lists on {@code
 * /admin/materials}, {@code /admin/locations} and {@code /ship-data} (REQ-ADMIN-001).
 *
 * @param page the zero-based page index, never negative
 * @param size the page size, one of {@link #PAGE_SIZES}
 * @param query the stripped name search, or {@code null} when none was given
 */
record CatalogueListPaging(int page, int size, @Nullable String query) {

  /** The page sizes the list footer offers. */
  static final @Unmodifiable List<Integer> PAGE_SIZES = List.of(25, 50, 100);

  /** The page size used when none or an unsupported one is requested. */
  static final int DEFAULT_PAGE_SIZE = 25;

  /** The {@code fragment} value that asks for the swapped results fragment only. */
  static final String RESULTS_FRAGMENT = "results";

  /**
   * Normalises the raw request parameters: a missing or negative page becomes 0, a size outside
   * {@link #PAGE_SIZES} becomes {@link #DEFAULT_PAGE_SIZE}, a blank query becomes {@code null}.
   *
   * @param page the requested page, may be {@code null}
   * @param size the requested size, may be {@code null}
   * @param query the requested search, may be {@code null}
   * @return the normalised paging
   */
  @Contract(pure = true)
  static @NotNull CatalogueListPaging of(
      @Nullable Integer page, @Nullable Integer size, @Nullable String query) {
    return new CatalogueListPaging(
        page == null || page < 0 ? 0 : page,
        size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE,
        query == null || query.isBlank() ? null : query.strip());
  }

  /**
   * Tells whether the request asks for the results fragment instead of the full page.
   *
   * @param fragment the {@code fragment} request parameter, may be {@code null}
   * @return {@code true} for {@code fragment=results}
   */
  @Contract(pure = true)
  static boolean wantsResults(@Nullable String fragment) {
    return RESULTS_FRAGMENT.equalsIgnoreCase(fragment);
  }

  /**
   * Substitutes an empty page for a missing backend body.
   *
   * @param response the backend page, may be {@code null}
   * @param <T> the row type
   * @return {@code response}, or an empty page at this paging's index and size
   */
  @NotNull
  <T> PageResponse<T> orEmpty(@Nullable PageResponse<T> response) {
    return response != null
        ? response
        : new PageResponse<>(List.of(), page, size, 0L, 0, List.of());
  }

  /**
   * Builds the encoded base URL the page links extend, keeping the search.
   *
   * @param path the page path
   * @return the path with the search as query parameter when one is set
   */
  @NotNull
  String baseUrl(@NotNull String path) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
    if (query != null) {
      builder.queryParam("query", query);
    }
    return builder.encode().build().toUriString();
  }

  /**
   * Adds the shared list attributes under the given prefix: {@code <prefix>Page}, {@code query},
   * {@code pageSize}, {@code pageSizes} and {@code paginationBaseUrl}.
   *
   * @param model the page model
   * @param prefix the list's model prefix, e.g. {@code materials}
   * @param response the page that is rendered
   * @param path the page path for the pagination links
   */
  void addTo(
      @NotNull Model model,
      @NotNull String prefix,
      @NotNull PageResponse<?> response,
      @NotNull String path) {
    model.addAttribute(prefix + "Page", response);
    model.addAttribute("query", query);
    model.addAttribute("pageSize", size);
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("paginationBaseUrl", baseUrl(path));
  }
}
