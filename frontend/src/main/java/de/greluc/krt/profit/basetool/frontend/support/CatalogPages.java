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
 * Page-walking helper for the "render the whole catalogue" admin surfaces (REQ-ADMIN-001,
 * ADR-0102).
 *
 * <p>Several admin pages (materials, locations, mission data, Spezialkommandos, UEX data, ship
 * data, the admin-settings pickers) render a complete reference catalogue and filter it
 * client-side. Before ADR-0102 each of them fetched exactly one bounded page ({@code size=1000} /
 * {@code size=10000}) and presented it as the full list — rows beyond the bound were silently
 * invisible and uneditable. This helper generalises the page-walk that {@code
 * PersonalInventoryBlueprintsPageController} introduced for issue #823: it pulls page after page
 * until the backend reports the last one, concatenating the contents, so the caller receives
 * <em>every</em> row regardless of catalogue size.
 *
 * <p>{@link #MAX_CATALOG_PAGES} bounds the walk as a safety net against a backend that reports
 * inconsistent page math (an unbounded loop would hang the request). Hitting that bound flips
 * {@link CompleteCatalog#truncated()} so the page can render a loud warning banner instead of
 * silently presenting a partial list as complete (REQ-ADMIN-002).
 */
public final class CatalogPages {

  /**
   * Hard upper bound on the number of pages assembled into one catalogue. At the smallest chunk
   * size in use ({@code size=1000}) this matches the backend's own {@code size} clamp ({@code
   * PaginationUtil.MAX_PAGE_SIZE}, 100 000 rows). It exists to bound a runaway loop should the
   * backend ever report an inconsistent {@code totalPages}; reaching it marks the result as
   * truncated rather than complete.
   */
  public static final int MAX_CATALOG_PAGES = 100;

  private CatalogPages() {}

  /**
   * A fully-assembled catalogue: every row the page walk gathered, the backend-reported total and
   * whether the walk had to stop early.
   *
   * @param items the concatenated page contents, in backend order (immutable)
   * @param totalElements the backend's authoritative total row count from the last page response —
   *     under truncation this still reflects the <em>full</em> catalogue size, so counts derived
   *     from it stay truthful (REQ-ADMIN-002); never less than {@code items.size()}
   * @param truncated {@code true} when the walk stopped at {@link #MAX_CATALOG_PAGES} while the
   *     backend still reported more pages — the caller must surface this loudly, never render the
   *     partial list as if it were complete
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
    public static <T> CompleteCatalog<T> empty() {
      return new CompleteCatalog<>(List.of(), 0L, false);
    }
  }

  /**
   * Fetches a complete catalogue by walking pages {@code 0..n} until the backend reports the last
   * page, an empty page arrives, or {@link #MAX_CATALOG_PAGES} is reached (which flags the result
   * as truncated). The caller supplies the actual page fetch — typically a {@code
   * BackendApiClient.get} with the zero-based page index appended as the {@code page} query
   * parameter; page stability across the walk is guaranteed by the backend's {@code id} sort
   * tiebreaker.
   *
   * <p>Exceptions thrown by {@code pageFetcher} propagate unchanged so every caller keeps its own
   * established error contract (error attribute, degraded empty list, async {@code exceptionally}
   * handler).
   *
   * @param pageFetcher fetches one page by zero-based index; a {@code null} response or {@code
   *     null} content ends the walk
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
      // The backend total is authoritative, but never report less than what was actually
      // gathered (defends against an endpoint that leaves totalElements unset).
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
