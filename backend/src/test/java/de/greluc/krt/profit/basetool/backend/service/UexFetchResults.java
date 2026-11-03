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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.UexClient;
import java.util.List;

/**
 * Factories for the {@link UexClient.FetchResult} envelopes that UEX sync-service tests stub onto a
 * mocked {@link UexClient}.
 */
final class UexFetchResults {

  private UexFetchResults() {}

  /**
   * Wraps rows as a normal {@code 200} outcome. Pass an empty list to model the empty-200
   * (outage-shaped) response the sync services must report as a problem.
   *
   * @param <T> the per-row payload type
   * @param rows the rows the stubbed endpoint should return
   * @return a complete fetch result carrying {@code rows}
   */
  static <T> UexClient.FetchResult<T> fetched(List<T> rows) {
    return UexClient.FetchResult.of(rows);
  }

  /**
   * The swallowed-failure outcome: no rows and {@code complete == false}. Use it to assert that a
   * sync service treats a failed fetch as "we never asked" rather than as "the feed dropped these
   * rows" (REQ-DATA-014).
   *
   * @param <T> the per-row payload type
   * @return an empty, incomplete fetch result
   */
  static <T> UexClient.FetchResult<T> failed() {
    return UexClient.FetchResult.partial(List.of());
  }
}
