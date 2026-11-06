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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * The orphan sweep of a P4K run (REQ-P4K-011, REQ-P4K-012, ADR-0336): per kind, on a valid census
 * only, it stamps {@code p4k_deleted_at} on every P4K-known row whose GUID the catalogue no longer
 * lists, unless that would stamp more than {@value #MASS_GUARD_PERCENT} % of the kind and the admin
 * did not override the guard. Runs after the reconcilers, inside the run's transaction; a preview
 * computes the same report and writes nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class P4kSweeper {

  /** The share of a kind's P4K-known rows above which the sweep needs the admin's override. */
  static final int MASS_GUARD_PERCENT = 5;

  /** The most ids one stamping query binds. */
  private static final int STAMP_BATCH = 500;

  private final EntityManager entityManager;

  /**
   * Sweeps every kind of the run.
   *
   * @param catalogue the catalogue
   * @param context the run, already reconciled
   * @param overrideMassGuard whether the admin ticked "apply marking anyway"
   * @return the kinds the sweep stamped only because of the override, for their audit events
   */
  @NotNull
  @Unmodifiable
  public List<P4kKind> sweep(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kImportContext context,
      boolean overrideMassGuard) {
    List<P4kKind> overridden = new ArrayList<>();
    for (P4kKind kind : P4kKind.values()) {
      if (sweepKind(catalogue, kind, context, overrideMassGuard)) {
        overridden.add(kind);
      }
    }
    return List.copyOf(overridden);
  }

  /**
   * Whether stamping a number of rows passes the mass guard of a kind.
   *
   * @param known the kind's P4K-known rows
   * @param toStamp the rows the sweep would stamp
   * @return {@code true} when that is more than {@value #MASS_GUARD_PERCENT} % of the known rows
   */
  static boolean exceedsMassGuard(int known, int toStamp) {
    return (long) toStamp * 100 > (long) known * MASS_GUARD_PERCENT;
  }

  /**
   * Sweeps one kind and records the outcome in the report.
   *
   * @param catalogue the catalogue
   * @param kind the kind
   * @param context the run
   * @param overrideMassGuard whether the admin overrode the mass guard
   * @return whether the kind was swept only because of the override
   */
  private boolean sweepKind(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kKind kind,
      @NotNull P4kImportContext context,
      boolean overrideMassGuard) {
    P4kImportReport report = context.getReport();
    List<? extends P4kRecord> records = catalogue.records(kind);
    P4kSweepTarget target = P4kSweepTarget.of(kind);
    if (!catalogue.head().isComplete(kind)) {
      report.sweep(kind, P4kSweepOutcome.SKIPPED_PARTIAL, knownRows(target).size(), 0);
      return false;
    }
    if (records.isEmpty()) {
      report.sweep(kind, P4kSweepOutcome.SKIPPED_EMPTY, knownRows(target).size(), 0);
      return false;
    }
    Set<UUID> listed = new HashSet<>();
    for (P4kRecord record : records) {
      UUID guid = record.parsedGuid();
      if (guid != null && !listed.add(guid)) {
        log.warn("P4K sweep of {} skipped: GUID {} occurs twice in the catalogue.", kind, guid);
        report.sweep(kind, P4kSweepOutcome.SKIPPED_DUPLICATE_GUID, knownRows(target).size(), 0);
        return false;
      }
    }
    List<P4kKnownRow> known = knownRows(target);
    List<P4kKnownRow> vanished =
        known.stream().filter(row -> !row.stamped() && !listed.contains(row.guid())).toList();
    boolean guarded = exceedsMassGuard(known.size(), vanished.size());
    if (guarded && !overrideMassGuard) {
      report.sweep(kind, P4kSweepOutcome.SKIPPED_MASS_GUARD, known.size(), vanished.size());
      return false;
    }
    report.sweep(kind, P4kSweepOutcome.SWEPT, known.size(), vanished.size());
    vanished.forEach(row -> report.vanished(kind, row));
    if (context.isApply()) {
      stamp(target, vanished, context);
    }
    return guarded;
  }

  /**
   * Reads the P4K-known rows of a kind.
   *
   * @param target the kind's table
   * @return every row with a P4K GUID
   */
  @NotNull
  private List<P4kKnownRow> knownRows(@NotNull P4kSweepTarget target) {
    return entityManager.createQuery(target.knownRowsQuery(), P4kKnownRow.class).getResultList();
  }

  /**
   * Stamps the vanished rows through their entities, so their version and update time move.
   *
   * @param target the kind's table
   * @param vanished the rows to stamp
   * @param context the run, for the stamp time
   */
  private void stamp(
      @NotNull P4kSweepTarget target,
      @NotNull List<P4kKnownRow> vanished,
      @NotNull P4kImportContext context) {
    List<UUID> ids = vanished.stream().map(P4kKnownRow::id).toList();
    for (int from = 0; from < ids.size(); from += STAMP_BATCH) {
      List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + STAMP_BATCH));
      entityManager
          .createQuery(target.rowsByIdQuery(), P4kKeyedRow.class)
          .setParameter("ids", batch)
          .getResultList()
          .forEach(row -> row.setP4kDeletedAt(context.getNow()));
    }
  }
}
