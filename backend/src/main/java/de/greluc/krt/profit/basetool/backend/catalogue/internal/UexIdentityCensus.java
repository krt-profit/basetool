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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;

/** Validates source-id membership and cross-source identity claims before a UEX import writes. */
public final class UexIdentityCensus {

  private final Set<Integer> ids = new HashSet<>();
  private final Map<UUID, Set<Integer>> uuidClaimants = new HashMap<>();
  private final Map<String, Set<Integer>> nameClaimants = new HashMap<>();
  private boolean complete;

  private UexIdentityCensus(boolean complete) {
    this.complete = complete;
  }

  /**
   * Counts distinct source identities and records every UUID and exact-name claimant.
   *
   * @param rows fetched source rows
   * @param sourceId source-id accessor
   * @param uuid in-game UUID accessor
   * @param name source display-name accessor
   * @param complete whether every required source fetch completed
   * @param <T> fetched source row type
   * @return the pre-write identity census
   */
  static <T> @NotNull UexIdentityCensus of(
      @NotNull Collection<T> rows,
      @NotNull Function<T, Integer> sourceId,
      @NotNull Function<T, String> uuid,
      @NotNull Function<T, String> name,
      boolean complete) {
    UexIdentityCensus census = new UexIdentityCensus(complete);
    for (T row : rows) {
      Integer id = sourceId.apply(row);
      if (id == null || id <= 0 || !census.ids.add(id)) {
        census.complete = false;
      }
      if (id == null || id <= 0) {
        continue;
      }
      UUID parsed = UexValues.parseUuid(uuid.apply(row));
      if (parsed != null) {
        census.uuidClaimants.computeIfAbsent(parsed, _ -> new HashSet<>()).add(id);
      }
      String key = nameKey(name.apply(row));
      if (key != null) {
        census.nameClaimants.computeIfAbsent(key, _ -> new HashSet<>()).add(id);
      }
    }
    return census;
  }

  /**
   * Returns the valid upstream source ids, including rows whose local write fails.
   *
   * @return an immutable source-id census
   */
  @NotNull
  Set<Integer> ids() {
    return Set.copyOf(ids);
  }

  /**
   * Reports whether the complete upstream membership was enumerated without invalid or repeated
   * ids.
   *
   * @return whether the source-id census permits an orphan sweep
   */
  boolean complete() {
    return complete;
  }

  /**
   * Checks whether an existing source id is still present upstream.
   *
   * @param id stored source id
   * @return whether the feed still contains the id
   */
  boolean containsId(@NotNull Integer id) {
    return ids.contains(id);
  }

  /**
   * Requires exactly one source id to claim the UUID in a complete feed.
   *
   * @param uuid incoming game UUID
   * @return whether its cross-source claim is unambiguous
   */
  boolean uniqueUuid(@Nullable UUID uuid) {
    return complete && uuid != null && uuidClaimants.getOrDefault(uuid, Set.of()).size() == 1;
  }

  /**
   * Requires exactly one source id to carry the exact name in a complete feed.
   *
   * @param name incoming display name
   * @return whether a name-based reconciliation is unique on the UEX side
   */
  boolean uniqueName(@Nullable String name) {
    String key = nameKey(name);
    return complete && key != null && nameClaimants.getOrDefault(key, Set.of()).size() == 1;
  }

  /**
   * Folds only case and surrounding whitespace, preserving every variant qualifier.
   *
   * @param name source display name
   * @return the exact-name lookup key, or {@code null} for blank input
   */
  static @Nullable String nameKey(@Nullable String name) {
    return StringUtils.hasText(name) ? name.strip().toLowerCase(Locale.ROOT) : null;
  }
}
