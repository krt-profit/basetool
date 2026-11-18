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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Reports which materials a module's member rows reference, so the P4K import hides or retires only
 * a material no member uses (REQ-P4K-075…078), and moves those references when a material merges
 * into another (REQ-DATA-046). Every implementation joins the caller's transaction ({@code
 * MANDATORY}).
 */
@ObserverSpi
public interface MaterialReferenceParticipant {

  /**
   * Runs a module's reference query: a JPQL selecting, among the material ids bound to {@code
   * :ids}, the ones the module's rows reference.
   *
   * @param entityManager the module's entity manager
   * @param jpql the query
   * @param materialIds the materials asked about
   * @return the referenced ones, empty when none is or none was asked about
   */
  @NotNull
  static Set<UUID> referenced(
      @NotNull EntityManager entityManager,
      @NotNull String jpql,
      @NotNull Collection<UUID> materialIds) {
    if (materialIds.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(
        entityManager
            .createQuery(jpql, UUID.class)
            .setParameter("ids", materialIds)
            .getResultList());
  }

  /**
   * Audits each row a merge moved onto the material that stays, one event per row.
   *
   * @param auditRecorder the audit trail
   * @param type the module's catalogue-reassignment event type
   * @param rows the moved rows
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @param subject the table of the moved rows
   * @return the number of moved rows
   */
  static int recordMoved(
      @NotNull AuditRecorder auditRecorder,
      @NotNull AuditEventType type,
      @NotNull List<UUID> rows,
      @NotNull UUID retiredId,
      @NotNull UUID survivorId,
      @NotNull String subject) {
    for (UUID row : rows) {
      auditRecorder.record(
          type,
          row,
          null,
          null,
          AuditDetails.of("retiredMaterial", retiredId)
              .with("survivorMaterial", survivorId)
              .with("subject", subject));
    }
    return rows.size();
  }

  /**
   * Tells which of the given materials one of the module's rows references.
   *
   * @param materialIds the materials asked about
   * @return the referenced ones among them, empty when none is
   */
  @NotNull
  Set<UUID> referencedMaterials(@NotNull Collection<UUID> materialIds);

  /**
   * Moves the module's references from a merged material to the one that stays, bumps the version
   * of every changed row and audits each in the module's own area. The caller holds both material
   * rows locked.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the number of rows whose reference moved
   */
  int reassignMaterial(@NotNull UUID retiredId, @NotNull UUID survivorId);
}
