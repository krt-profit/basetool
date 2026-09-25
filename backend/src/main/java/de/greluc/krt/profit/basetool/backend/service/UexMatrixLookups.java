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

import de.greluc.krt.profit.basetool.backend.repository.PairKeyRef;
import de.greluc.krt.profit.basetool.backend.repository.UexKeyRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Preloaded id maps a UEX matrix sync resolves every row against: parent and terminal ids by UEX
 * id, and existing matrix-row ids by (parent, terminal) pair.
 *
 * <p>Holds ids only, so it serves every chunk transaction of a run. Rows created during the run are
 * recorded via {@link #rememberParent} / {@link #rememberRow}, so later chunks and replays update
 * rather than duplicate them.
 */
final class UexMatrixLookups {

  /** Catalogue parent id by its UEX id ({@code id_commodity}, the UEX item id). */
  private final Map<Integer, UUID> parents;

  /** Terminal id by {@code id_terminal}. */
  private final Map<Integer, UUID> terminals;

  /** Existing matrix row id by its (parent, terminal) pair. */
  private final Map<Pair, UUID> rows;

  /**
   * Builds the lookups from the three projection reads.
   *
   * @param parentRefs the catalogue parents' (UEX id, local id) rows
   * @param terminalRefs the terminals' (UEX id, local id) rows
   * @param rowRefs the matrix rows' (parent, terminal, id) rows
   */
  UexMatrixLookups(
      @NotNull List<UexKeyRef> parentRefs,
      @NotNull List<UexKeyRef> terminalRefs,
      @NotNull List<PairKeyRef> rowRefs) {
    this.parents = byUexId(parentRefs);
    this.terminals = byUexId(terminalRefs);
    this.rows = new HashMap<>();
    for (PairKeyRef ref : rowRefs) {
      rows.put(new Pair(ref.getParentId(), ref.getTerminalId()), ref.getId());
    }
  }

  /**
   * The local id of the catalogue parent with this UEX id.
   *
   * @param uexId the UEX id; may be {@code null}
   * @return the local id, or {@code null} when unknown
   */
  @Nullable
  UUID parentId(@Nullable Integer uexId) {
    return uexId == null ? null : parents.get(uexId);
  }

  /**
   * The local id of the terminal with this UEX id.
   *
   * @param uexId the UEX {@code id_terminal}; may be {@code null}
   * @return the local id, or {@code null} when unknown
   */
  @Nullable
  UUID terminalId(@Nullable Integer uexId) {
    return uexId == null ? null : terminals.get(uexId);
  }

  /**
   * The id of the existing matrix row for this pair.
   *
   * @param parentId the local parent id
   * @param terminalId the local terminal id
   * @return the row id, or {@code null} when the pair has no row yet
   */
  @Nullable
  UUID rowId(@NotNull UUID parentId, @NotNull UUID terminalId) {
    return rows.get(new Pair(parentId, terminalId));
  }

  /**
   * Records a parent created during the run.
   *
   * @param uexId its UEX id
   * @param id its local id
   */
  void rememberParent(@NotNull Integer uexId, @NotNull UUID id) {
    parents.put(uexId, id);
  }

  /**
   * Records a matrix row written during the run.
   *
   * @param parentId the local parent id
   * @param terminalId the local terminal id
   * @param id the row id
   */
  void rememberRow(@NotNull UUID parentId, @NotNull UUID terminalId, @NotNull UUID id) {
    rows.put(new Pair(parentId, terminalId), id);
  }

  /**
   * Keys projection rows by UEX id, the first winning on a duplicate (the columns are unique).
   *
   * @param refs the projection rows
   * @return a mutable map from UEX id to local id
   */
  @NotNull
  private static Map<Integer, UUID> byUexId(@NotNull List<UexKeyRef> refs) {
    Map<Integer, UUID> map = new HashMap<>();
    for (UexKeyRef ref : refs) {
      map.putIfAbsent(ref.getUexId(), ref.getId());
    }
    return map;
  }

  /**
   * A (parent, terminal) pair — the unique key of a matrix table.
   *
   * @param parentId the catalogue parent id
   * @param terminalId the terminal id
   */
  record Pair(UUID parentId, UUID terminalId) {}
}
