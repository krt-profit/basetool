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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDeletionObserver;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Retires the leftover materials an APPLY settles (REQ-P4K-076…078): it deletes a twin whose
 * identity moved to the material that stays, or hides a row. The change report names each
 * retirement; an APPLY writes it and audits it as {@code CATALOGUE_MATERIAL_LEFTOVER_RETIRED}, a
 * preview only reports it. A deletion and a handover are flushed at once, so the material that
 * stays can take the retired row's unique keys in the same run.
 */
@Component
@RequiredArgsConstructor
class P4kMaterialRetirement {

  /** The report column of a hide. */
  static final String HIDDEN_COLUMN = "p4k_hidden_at";

  private final MaterialRepository materialRepository;
  private final MaterialDeletionObserver deletionObserver;
  private final AuditRecorder auditRecorder;

  /**
   * Deletes a twin that nothing references once its identity moved to the material that stays,
   * reported {@code MERGED}.
   *
   * @param twin the material deleted
   * @param survivor the material that takes its identity
   * @param record the record the report row names
   * @param rule the rule that retires the twin
   * @param context the run
   */
  void delete(
      @NotNull Material twin,
      @NotNull Material survivor,
      @NotNull P4kRecord record,
      @NotNull P4kLeftoverRule rule,
      @NotNull P4kImportContext context) {
    context.getReport().merged(P4kKind.COMMODITY, record, twin.getName(), survivor.getName());
    if (!context.isApply()) {
      return;
    }
    UUID id = Objects.requireNonNull(twin.getId());
    deletionObserver.beforeMaterialDelete(id);
    materialRepository.delete(twin);
    materialRepository.flush();
    audit(id, twin.getName(), rule, true, survivor, context);
  }

  /**
   * Hides a material as an admin's hide would (REQ-P4K-016), reported {@code CHANGED
   * p4k_hidden_at}; its references stay.
   *
   * @param row the material
   * @param record the record the report row names
   * @param rule the rule that retires the row
   * @param context the run
   */
  void hide(
      @NotNull Material row,
      @NotNull P4kRecord record,
      @NotNull P4kLeftoverRule rule,
      @NotNull P4kImportContext context) {
    reportHide(record, context);
    if (context.isApply()) {
      row.setP4kHiddenAt(context.getNow());
      audit(Objects.requireNonNull(row.getId()), row.getName(), rule, false, null, context);
    }
  }

  /**
   * Hides a seeded commodity that hands its P4K identity to its twin but stays, because a catalogue
   * row references it: it gives up its GUID, its {@code ResourceType} and its name, each reported
   * {@code CHANGED}, so the twin can take them in the same run.
   *
   * @param row the seeded material
   * @param freedName the name it takes so the twin can take its own
   * @param twin the material that takes its identity
   * @param record the record the report rows name
   * @param context the run
   */
  void handOver(
      @NotNull Material row,
      @NotNull String freedName,
      @NotNull Material twin,
      @NotNull P4kRecord record,
      @NotNull P4kImportContext context) {
    P4kImportReport report = context.getReport();
    report.changed(
        P4kKind.COMMODITY, record, "p4k_uuid", Objects.toString(row.getP4kUuid(), null), null);
    report.changed(P4kKind.COMMODITY, record, "name", row.getName(), freedName);
    reportHide(record, context);
    if (!context.isApply()) {
      return;
    }
    row.setP4kUuid(null);
    row.setP4kSubtypeUuid(null);
    row.setName(freedName);
    row.setP4kHiddenAt(context.getNow());
    materialRepository.flush();
    audit(
        Objects.requireNonNull(row.getId()),
        row.getName(),
        P4kLeftoverRule.SEEDED_NOT_CREATABLE,
        false,
        twin,
        context);
  }

  /**
   * Reports a hide as {@code CHANGED p4k_hidden_at}.
   *
   * @param record the record the report row names
   * @param context the run
   */
  private static void reportHide(@NotNull P4kRecord record, @NotNull P4kImportContext context) {
    context
        .getReport()
        .changed(P4kKind.COMMODITY, record, HIDDEN_COLUMN, null, context.getNow().toString());
  }

  /**
   * Records the retirement's audit event.
   *
   * @param id the retired material
   * @param name its name, the event's label
   * @param rule the rule that retired it
   * @param deleted whether it was deleted rather than hidden
   * @param twin the material that took its identity, or {@code null}
   * @param context the run
   */
  private void audit(
      @NotNull UUID id,
      @NotNull String name,
      @NotNull P4kLeftoverRule rule,
      boolean deleted,
      @Nullable Material twin,
      @NotNull P4kImportContext context) {
    AuditDetails details = AuditDetails.of("rule", rule).with("deleted", deleted);
    if (twin != null) {
      details.with("twin", twin.getId());
    }
    auditRecorder.record(
        AuditEventType.CATALOGUE_MATERIAL_LEFTOVER_RETIRED,
        id,
        name,
        null,
        details.with("run", context.getRunId()));
  }
}
