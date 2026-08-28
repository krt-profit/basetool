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

package de.greluc.krt.profit.basetool.backend.dto.scwiki;

import java.util.UUID;

/**
 * Row identity every payload of a paginated SC Wiki list endpoint carries.
 *
 * <p>Exists so the {@code ScWikiClient} page walk can answer one question without knowing the
 * concrete payload type: <em>did this walk see the same row twice?</em> A re-served row is the
 * signature of a pagination window that shifted while the walk ran (a row inserted or deleted
 * upstream moves every later row across the page boundaries), and it means some other row was
 * pushed out of the window and never fetched. The merged list then has the right shape and the
 * wrong content — which matters because that list drives the tombstone sweeps: every catalogue row
 * missing from it is marked {@code scwiki_deleted}.
 *
 * <p>Counting <em>distinct</em> {@link #uuid()} values rather than rows is what separates "the walk
 * enumerated the feed" from "the walk saw 12 331 rows, 48 of them twice". {@code
 * ScWikiClient.fetchAllPagesResult} therefore bounds its type parameter on this interface instead
 * of accepting any payload and trusting the caller to have deduplicated — a paginated endpoint
 * whose rows carry no identity cannot be census-checked at all, and the compiler now says so.
 */
public interface ScWikiRow {

  /**
   * The Wiki's stable identifier for this row — the same key the {@code game_item} / {@code
   * manufacturer} cross-references and the tombstone sweeps' seen-sets are keyed on, which is why
   * it is also the right identity for the census cross-check.
   *
   * @return the row's Wiki UUID, or {@code null} for a payload the upstream served without one
   *     (counted as its own non-deduplicable row rather than collapsed with every other id-less
   *     row)
   */
  UUID uuid();
}
