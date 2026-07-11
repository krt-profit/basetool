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

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Clamps and normalises the client-supplied board query parameters of the Materialbörse read model
 * (REQ-MARKET-001…) — the page index/size, the free-text search fragment, the minimum-quality floor
 * and the sort key. Extracted verbatim out of the material-exchange read service so the paging and
 * filter-normalisation idiom lives in one leaf helper rather than inline on the query path.
 *
 * <p>Every method is null-tolerant and total: a {@code null} or out-of-range client value collapses
 * to the safe default (an unbounded search, an unfiltered quality, page 0, the {@value
 * #DEFAULT_PAGE_SIZE}-row default page, the {@code qual} default sort) rather than raising.
 */
public final class MaterialExchangeQueryParams {

  /** Default board page size when the caller does not specify one. */
  public static final int DEFAULT_PAGE_SIZE = 50;

  /** Upper bound on the board page size — the list is scrollable, not deeply paginated. */
  public static final int MAX_PAGE_SIZE = 500;

  private MaterialExchangeQueryParams() {}

  /**
   * Normalises a search term into a lowercased {@code %fragment%} LIKE pattern, or {@code null}
   * when blank.
   *
   * @param query the raw search term.
   * @return the LIKE pattern, or {@code null}.
   */
  public static @Nullable String normalizeQuery(@Nullable String query) {
    if (query == null || query.isBlank()) {
      return null;
    }
    return LikePatterns.contains(query.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * Clamps a client minimum-quality filter to a non-negative int (0 disables the filter).
   *
   * @param minQuality the raw value.
   * @return the clamped value.
   */
  public static int clampQuality(@Nullable Integer minQuality) {
    return minQuality == null ? 0 : Math.max(0, minQuality);
  }

  /**
   * Clamps a client page index to a non-negative int.
   *
   * @param page the raw value.
   * @return the clamped value.
   */
  public static int clampPage(@Nullable Integer page) {
    return page == null || page < 0 ? 0 : page;
  }

  /**
   * Clamps a client page size into {@code [1, MAX_PAGE_SIZE]}, defaulting when absent.
   *
   * @param size the raw value.
   * @return the clamped value.
   */
  public static int clampSize(@Nullable Integer size) {
    if (size == null || size <= 0) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  /**
   * Normalises a client board sort key to one of the whitelisted, non-null keys the board query's
   * embedded {@code ORDER BY} understands — {@code menge} / {@code mat} / {@code neu}, else {@code
   * qual} (the default, also for {@code null} or any unrecognised value). The query itself spans
   * both offer kinds via {@code COALESCE}: {@code menge} sorts on the effective offered quantity
   * {@code COALESCE(LEAST(offeredAmount, item.amount), itemQuantity)} — a material offer ranks by
   * what is actually on offer (clamped to stock, ADR-0086), an item offer by its stated quantity —
   * always with a stable {@code releasedAt desc, id desc} tiebreaker so pagination stays
   * deterministic.
   *
   * @param key the raw client sort key, or {@code null}.
   * @return the normalised sort key, never {@code null}.
   */
  public static String normalizeSort(@Nullable String key) {
    if (key == null) {
      return "qual";
    }
    return switch (key) {
      case "menge", "mat", "neu" -> key;
      default -> "qual";
    };
  }
}
