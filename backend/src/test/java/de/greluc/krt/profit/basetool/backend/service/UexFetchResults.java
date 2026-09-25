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

import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import java.util.List;

/**
 * Factories for the {@link UexClient.FetchResult} envelopes that UEX sync-service tests stub onto a
 * mocked {@link UexClient}.
 */
final class UexFetchResults {

  private UexFetchResults() {}

  /**
   * Wraps rows as a normal {@code 200} outcome: the feed answered with content and is therefore
   * <em>not</em> flagged unchanged. Pass an empty list to model the empty-200 (outage-shaped)
   * response the sync services must report as a problem.
   *
   * @param <T> the per-row payload type
   * @param rows the rows the stubbed endpoint should return
   * @return a fetch result carrying {@code rows} with {@code notModified == false}
   */
  static <T> UexClient.FetchResult<T> fetched(List<T> rows) {
    return UexClient.FetchResult.of(rows);
  }

  /**
   * The {@code 304 Not Modified} outcome: no rows, flagged unchanged. Use it to assert that a sync
   * service logs the healthy fully-cached run instead of its "no data received" WARN, and that it
   * leaves the local catalogue alone.
   *
   * @param <T> the per-row payload type
   * @return a fetch result with an empty row list and {@code notModified == true}
   */
  static <T> UexClient.FetchResult<T> unchanged() {
    return UexClient.FetchResult.unchanged();
  }

  /**
   * The swallowed-failure outcome: no rows, {@code notModified == false} and — the part that
   * matters — {@code complete == false}. Use it to assert that a sync service treats a failed fetch
   * as "we never asked" rather than as "the feed dropped these rows" (REQ-DATA-014).
   *
   * @param <T> the per-row payload type
   * @return an empty, incomplete fetch result
   */
  static <T> UexClient.FetchResult<T> failed() {
    return UexClient.FetchResult.partial(List.of());
  }
}
