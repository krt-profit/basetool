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
 * Builders for the {@link UexClient.FetchResult} envelopes the UEX sync-service tests stub onto
 * their mocked {@link UexClient}.
 *
 * <p>The client's list getters return the outcome record rather than a bare {@code List} since H6,
 * because a caller that only sees an empty list cannot tell an unchanged feed ({@code 304}) from a
 * broken one — and every sync service greeted both with the same alarming "no data" WARN. Stubbing
 * that record inline reads as {@code new UexClient.FetchResult<>(List.of(dto), false)} at every one
 * of the ~100 call sites, which buries the actual fixture behind ceremony and overruns the
 * 100-column limit; these two factories keep the stub about the data again.
 *
 * <p>Deliberately mirrors the private {@code fetched} / {@code unchanged} helpers {@code
 * UexItemSyncServiceTest} already grew for the same reason — that test keeps its own because its
 * varargs shape is tailored to the per-category walk.
 */
final class UexFetchResults {

  private UexFetchResults() {
    // Test fixture holder — not instantiable.
  }

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
    return new UexClient.FetchResult<>(rows, false);
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
    return new UexClient.FetchResult<>(List.of(), true);
  }
}
