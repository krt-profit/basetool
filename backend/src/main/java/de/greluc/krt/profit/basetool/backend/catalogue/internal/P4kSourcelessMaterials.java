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
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Hides the UEX commodities no game source knows (REQ-P4K-075, ADR-0396): a material only UEX
 * lists, without a P4K GUID, a SC Wiki UUID or a {@code ResourceType}, that the game files do not
 * know by name either, with no price and no reference ({@link MaterialReferenceParticipant}), is
 * marked {@code sourceless_hidden_at} by an APPLY and leaves the lists and pickers while it stays
 * so. An APPLY lifts the mark of a row that no longer qualifies; a source or a price lifts the hide
 * at once ({@link Material#isSourcelessHidden()}). Every change is reported and audited.
 */
@Component
@RequiredArgsConstructor
public class P4kSourcelessMaterials {

  /** The report and audit column of the mark. */
  static final String COLUMN = "sourceless_hidden_at";

  /** The marked materials and the UEX-only materials without a game identity. */
  private static final String CANDIDATES =
      "SELECT m FROM Material m WHERE m.sourcelessHiddenAt IS NOT NULL"
          + " OR (m.sourceSystems = :uexOnly AND m.p4kUuid IS NULL AND m.scwikiUuid IS NULL"
          + " AND m.p4kSubtypeUuid IS NULL)";

  private final EntityManager entityManager;
  private final List<MaterialReferenceParticipant> participants;
  private final AuditRecorder auditRecorder;

  /**
   * Marks the materials that qualify and lifts the mark of those that no longer do; a preview only
   * reports it.
   *
   * @param known the materials the game files know, those the run matched included
   * @param context the run
   */
  public void review(@NotNull Set<UUID> known, @NotNull P4kImportContext context) {
    List<Material> rows =
        entityManager
            .createQuery(CANDIDATES, Material.class)
            .setParameter("uexOnly", MaterialSourceSystem.UEX_ONLY)
            .getResultList();
    Set<UUID> sourceless = new HashSet<>();
    for (Material row : rows) {
      UUID id = row.getId();
      if (id != null && isSourceless(row) && !known.contains(id)) {
        sourceless.add(id);
      }
    }
    Set<UUID> referenced = new HashSet<>();
    participants.forEach(
        participant -> referenced.addAll(participant.referencedMaterials(sourceless)));
    for (Material row : rows) {
      UUID id = Objects.requireNonNull(row.getId());
      boolean hide = sourceless.contains(id) && !referenced.contains(id);
      Instant marked = row.getSourcelessHiddenAt();
      if (hide && marked == null) {
        change(row, null, context.getNow(), context);
      } else if (!hide && marked != null) {
        change(row, marked, null, context);
      }
    }
  }

  /**
   * Whether a material is a live UEX commodity no game source knows and no price lists.
   *
   * @param row the material
   * @return {@code true} when it qualifies before its references are asked
   */
  private static boolean isSourceless(@NotNull Material row) {
    return row.getSourceSystems() == MaterialSourceSystem.UEX_ONLY
        && row.isWithoutGameSource()
        && !row.isPriced()
        && !row.isP4kRetired();
  }

  /**
   * Sets or lifts the mark of a material: reported as {@code CHANGED sourceless_hidden_at} and, on
   * an APPLY, written and audited.
   *
   * @param row the material
   * @param from the mark before, {@code null} when unmarked
   * @param to the mark after, {@code null} to lift it
   * @param context the run
   */
  private void change(
      @NotNull Material row,
      @Nullable Instant from,
      @Nullable Instant to,
      @NotNull P4kImportContext context) {
    context
        .getReport()
        .changed(
            P4kKind.COMMODITY,
            new P4kMaterialRecord(row),
            COLUMN,
            Objects.toString(from, null),
            Objects.toString(to, null));
    if (!context.isApply()) {
      return;
    }
    row.setSourcelessHiddenAt(to);
    auditRecorder.record(
        AuditEventType.CATALOGUE_MATERIAL_SOURCELESS_VISIBILITY_CHANGED,
        row.getId(),
        row.getName(),
        null,
        AuditDetails.of("hidden", to != null).with("run", context.getRunId()));
  }
}
