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

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The rows of one kind by P4K GUID, for the catalogue's references between kinds (paint ships,
 * loadout ports): every row that holds a GUID, overlaid with the rows the run matched, so a preview
 * resolves a match it has not written.
 */
final class P4kRowIds {

  /** The row id per P4K GUID. */
  private final Map<UUID, UUID> idByGuid;

  /**
   * Wraps a filled index.
   *
   * @param idByGuid the row id per P4K GUID
   */
  private P4kRowIds(@NotNull Map<UUID, UUID> idByGuid) {
    this.idByGuid = idByGuid;
  }

  /**
   * Returns the index of a kind; call it after the kind's reconciler, inside the run's transaction.
   * The run reads it once and hands every later caller the same index, since no later step adds a
   * row of that kind.
   *
   * @param entityManager the run's entity manager
   * @param kind the kind
   * @param context the run
   * @return the index
   */
  @NotNull
  static P4kRowIds load(
      @NotNull EntityManager entityManager,
      @NotNull P4kKind kind,
      @NotNull P4kImportContext context) {
    return context.rowIds(kind, _ -> read(entityManager, kind, context));
  }

  /**
   * Reads the index of a kind: one query, overlaid with the run's matches.
   *
   * @param entityManager the run's entity manager
   * @param kind the kind
   * @param context the run
   * @return the index
   */
  @NotNull
  private static P4kRowIds read(
      @NotNull EntityManager entityManager,
      @NotNull P4kKind kind,
      @NotNull P4kImportContext context) {
    Map<UUID, UUID> idByGuid = new HashMap<>();
    entityManager
        .createQuery(P4kSweepTarget.of(kind).knownRowsQuery(), P4kKnownRow.class)
        .getResultList()
        .forEach(row -> idByGuid.put(row.guid(), row.id()));
    for (Map.Entry<UUID, P4kKeyedRow> entry : context.stampedRows(kind).entrySet()) {
      UUID id = entry.getValue().getId();
      if (id != null) {
        idByGuid.put(entry.getKey(), id);
      }
    }
    return new P4kRowIds(idByGuid);
  }

  /**
   * Returns the row of a GUID.
   *
   * @param guid the GUID text, or {@code null}
   * @return the row id, or {@code null} when no row holds the GUID
   */
  @Nullable
  UUID id(@Nullable String guid) {
    UUID parsed = P4kGuids.parse(guid);
    return parsed == null ? null : idByGuid.get(parsed);
  }
}
