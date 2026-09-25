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

package de.greluc.krt.profit.basetool.frontend.support;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.jetbrains.annotations.NotNull;

/**
 * Fetches a complete reference catalogue for the admin pages by walking all backend pages
 * (REQ-ADMIN-001, ADR-0102).
 *
 * <p>The walk stops at {@link #MAX_CATALOG_PAGES}; hitting that bound sets {@link
 * CompleteCatalog#truncated()} so the page can warn instead of presenting a partial list
 * (REQ-ADMIN-002).
 */
public final class CatalogPages {

  /**
   * Upper bound on the pages assembled into one catalogue, guarding against inconsistent backend
   * page counts; reaching it marks the result truncated.
   */
  public static final int MAX_CATALOG_PAGES = 100;

  private CatalogPages() {}

  /**
   * A fully assembled catalogue.
   *
   * @param items the concatenated page contents in backend order (immutable)
   * @param totalElements the backend's total row count, which reflects the full catalogue even when
   *     truncated
   * @param truncated {@code true} when the walk stopped at {@link #MAX_CATALOG_PAGES} with pages
   *     remaining; the caller must surface this
   * @param <T> the page item type
   */
  public record CompleteCatalog<T>(List<T> items, long totalElements, boolean truncated) {

    /**
     * An empty, non-truncated catalogue — the degraded value for callers that swallow a backend
     * failure and render an empty list instead of failing the whole page.
     *
     * @param <T> the page item type
     * @return a catalogue with no items, a total of zero and {@code truncated=false}
     */
    @NotNull
    public static <T> CompleteCatalog<T> empty() {
      return new CompleteCatalog<>(List.of(), 0L, false);
    }
  }

  /**
   * Walks pages {@code 0..n} until the last page, an empty page, or {@link #MAX_CATALOG_PAGES}
   * (which flags the result truncated). Exceptions from {@code pageFetcher} propagate unchanged.
   *
   * @param pageFetcher fetches one page by zero-based index; a {@code null} response or content
   *     ends the walk
   * @param <T> the page item type
   * @return the assembled catalogue; never {@code null}
   */
  @NotNull
  public static <T> CompleteCatalog<T> fetchAll(@NotNull IntFunction<PageResponse<T>> pageFetcher) {
    List<T> items = new ArrayList<>();
    long totalElements = 0L;
    boolean truncated = false;
    int page = 0;
    while (true) {
      PageResponse<T> response = pageFetcher.apply(page);
      if (response == null || response.content() == null) {
        break;
      }
      items.addAll(response.content());
      totalElements = Math.max(response.totalElements(), items.size());
      if (response.content().isEmpty() || page + 1 >= response.totalPages()) {
        break;
      }
      if (page + 1 >= MAX_CATALOG_PAGES) {
        truncated = true;
        break;
      }
      page++;
    }
    return new CompleteCatalog<>(List.copyOf(items), totalElements, truncated);
  }
}
