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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexItemDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Decides which row a second UEX listing of a game item created belongs to (REQ-DATA-044). UEX
 * lists some items twice: one listing sits on the game row, the other created a UEX-only row with
 * no game UUID. Such a twin belongs to the game row its listing's game UUID names when that row
 * alone holds the UUID and carries the listing's name; else, when no row holds the listing's UUID,
 * to the single visible game row carrying its name, or to the {@code _GS} row of two such rows. The
 * game row must hold another listing of the same complete fetch.
 */
final class UexItemTwins {

  /** The candidate rows by id. */
  @NotNull private final Map<UUID, GameItem> rows = new HashMap<>();

  /** The ids of the rows holding a game UUID as P4K GUID or external UUID, by that UUID. */
  @NotNull private final Map<UUID, Set<UUID>> holders = new HashMap<>();

  /** The ids of the rows carrying a name as name or UEX name, by the folded name. */
  @NotNull private final Map<String, Set<UUID>> namesakes = new HashMap<>();

  /** The fetch's identity census, which tells whether a game row's own listing is live. */
  @NotNull private final UexIdentityCensus census;

  /**
   * Indexes the candidate rows.
   *
   * @param candidates the rows holding a listing's game UUID or carrying a listing's name
   * @param census the complete fetch's identity census
   */
  UexItemTwins(@NotNull Collection<GameItem> candidates, @NotNull UexIdentityCensus census) {
    this.census = census;
    for (GameItem row : candidates) {
      rows.put(row.getId(), row);
      for (UUID uuid : Arrays.asList(row.getP4kUuid(), row.getExternalUuid())) {
        if (uuid != null) {
          holders.computeIfAbsent(uuid, _ -> new LinkedHashSet<>()).add(row.getId());
        }
      }
      for (String name : Arrays.asList(row.getName(), row.getUexName())) {
        String key = UexIdentityCensus.nameKey(name);
        if (key != null) {
          namesakes.computeIfAbsent(key, _ -> new LinkedHashSet<>()).add(row.getId());
        }
      }
    }
  }

  /**
   * Tells whether a row may be a twin: only a UEX listing wrote it, and it holds a UEX item id but
   * neither a P4K GUID nor an external UUID.
   *
   * @param row the row
   * @return whether the twin rule considers it
   */
  static boolean isTwinCandidate(@NotNull GameItem row) {
    return row.getSourceSystems() == GameItemSourceSystem.UEX_ONLY
        && row.getUexItemId() != null
        && row.getExternalUuid() == null
        && row.getP4kUuid() == null;
  }

  /**
   * Tells whether a row is a game row: it holds a P4K GUID, or an external UUID on a row another
   * source than UEX wrote.
   *
   * @param row the row
   * @return whether a twin may merge into it
   */
  static boolean isGameRow(@NotNull GameItem row) {
    return row.getP4kUuid() != null
        || (row.getExternalUuid() != null
            && row.getSourceSystems() != GameItemSourceSystem.UEX_ONLY);
  }

  /**
   * Finds the game row a twin belongs to.
   *
   * @param twin the UEX-only row
   * @param listing the twin's listing of the fetch
   * @return the merge, or {@code null} when no rule finds a single game row
   */
  @Nullable
  Twin find(@NotNull GameItem twin, @NotNull UexItemDto listing) {
    String name = UexIdentityCensus.nameKey(listing.name());
    if (!isTwinCandidate(twin) || name == null) {
      return null;
    }
    UUID uuid = UexValues.parseUuid(listing.uuid());
    Set<UUID> holding = uuid == null ? Set.of() : holders.getOrDefault(uuid, Set.of());
    if (!holding.isEmpty()) {
      GameItem holder = holding.size() == 1 ? rows.get(holding.iterator().next()) : null;
      return holder != null && isGameRow(holder) && carries(holder, name)
          ? twin(twin, holder, UexItemTwinRule.GAME_UUID)
          : null;
    }
    List<GameItem> visible = new ArrayList<>();
    for (UUID id : namesakes.getOrDefault(name, Set.of())) {
      GameItem row = rows.get(id);
      if (row != null && isGameRow(row) && row.isSelectable()) {
        visible.add(row);
      }
    }
    if (visible.size() == 1) {
      return twin(twin, visible.getFirst(), UexItemTwinRule.UNIQUE_NAME);
    }
    GameItem gsRow = visible.size() == 2 ? gsRow(visible.get(0), visible.get(1)) : null;
    return gsRow == null ? null : twin(twin, gsRow, UexItemTwinRule.GS_RECORD);
  }

  /**
   * Pairs a twin with a game row that holds another listing of the fetch.
   *
   * @param twin the UEX-only row
   * @param target the game row
   * @param rule the evidence
   * @return the merge, or {@code null} when the game row holds no live listing of its own
   */
  @Nullable
  private Twin twin(
      @NotNull GameItem twin, @NotNull GameItem target, @NotNull UexItemTwinRule rule) {
    Integer own = target.getUexItemId();
    return own == null || own.equals(twin.getUexItemId()) || !census.containsId(own)
        ? null
        : new Twin(twin.getId(), target.getId(), rule);
  }

  /**
   * Tells whether a row carries a folded name as its name or its UEX name.
   *
   * @param row the row
   * @param name the folded name
   * @return whether it does
   */
  private static boolean carries(@NotNull GameItem row, @NotNull String name) {
    return name.equals(UexIdentityCensus.nameKey(row.getName()))
        || name.equals(UexIdentityCensus.nameKey(row.getUexName()));
  }

  /**
   * Returns the one of two rows whose class name is the other's plus {@code _GS}.
   *
   * @param first one row
   * @param second the other row
   * @return the {@code _GS} row, or {@code null} when the class names are no such pair
   */
  @Nullable
  private static GameItem gsRow(@NotNull GameItem first, @NotNull GameItem second) {
    if (supersedes(first, second)) {
      return first;
    }
    return supersedes(second, first) ? second : null;
  }

  /**
   * Tells whether a row's class name is another's plus {@code _GS}, ignoring case.
   *
   * @param successor the row that may hold the {@code _GS} record
   * @param old the row that may hold the old record
   * @return whether it does
   */
  private static boolean supersedes(@NotNull GameItem successor, @NotNull GameItem old) {
    String successorKey = P4kMatchKeys.fold(successor.getClassName());
    String oldKey = P4kMatchKeys.fold(old.getClassName());
    return successorKey != null
        && oldKey != null
        && !oldKey.isEmpty()
        && Objects.equals(P4kShipGoldStandard.oldClassName(successorKey), oldKey);
  }

  /**
   * One twin and the game row it merges into.
   *
   * @param twinId the UEX-only row
   * @param targetId the game row
   * @param rule the evidence
   */
  record Twin(@NotNull UUID twinId, @NotNull UUID targetId, @NotNull UexItemTwinRule rule) {}
}
