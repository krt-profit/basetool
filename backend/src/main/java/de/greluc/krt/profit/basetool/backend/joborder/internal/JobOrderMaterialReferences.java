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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceParticipant;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports the materials job orders reference (REQ-P4K-075): material lines, item-order material
 * lines, handovers, claims and provision credits; and moves them onto the material a merge keeps
 * (REQ-DATA-046).
 */
@Component
@RequiredArgsConstructor
public class JobOrderMaterialReferences implements MaterialReferenceParticipant {

  /** The materials among {@code :ids} a job-order row references. */
  private static final String REFERENCED =
      "SELECT m.material.id FROM JobOrderMaterial m WHERE m.material.id IN :ids"
          + " UNION SELECT im.material.id FROM JobOrderItemMaterial im"
          + " WHERE im.material.id IN :ids"
          + " UNION SELECT h.material.id FROM JobOrderHandoverItem h WHERE h.material.id IN :ids"
          + " UNION SELECT c.material.id FROM MaterialClaim c WHERE c.material.id IN :ids"
          + " UNION SELECT p.materialId FROM MaterialProvisionCredit p WHERE p.materialId IN :ids";

  /** The order sections that show a material. */
  private static final List<String> ORDER_SECTIONS =
      List.of("materials", "aggregated", "kpi", "actions", "participations", "items");

  private final EntityManager entityManager;
  private final JobOrderMaterialReassignmentRepository reassignments;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSyncPublisher;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<UUID> referencedMaterials(@NotNull Collection<UUID> materialIds) {
    return MaterialReferenceParticipant.referenced(entityManager, REFERENCED, materialIds);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int reassignMaterial(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    List<UUID> orders = reassignments.orders(retiredId);
    int changed =
        record(
                reassignments.lines(retiredId, survivorId),
                retiredId,
                survivorId,
                "job_order_material")
            + record(
                reassignments.itemLines(retiredId, survivorId),
                retiredId,
                survivorId,
                "job_order_item_material")
            + record(
                reassignments.handovers(retiredId, survivorId),
                retiredId,
                survivorId,
                "job_order_handover_item")
            + record(
                reassignments.claims(retiredId, survivorId),
                retiredId,
                survivorId,
                "material_claim")
            + record(
                reassignments.credits(retiredId, survivorId),
                retiredId,
                survivorId,
                "material_provision_credit");
    if (changed > 0) {
      for (UUID order : orders) {
        liveSyncPublisher.publishAfterCommit(
            LiveSyncTopic.of(LiveSyncTopicClass.ORDER, order), ORDER_SECTIONS);
      }
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.ORDERS_QUEUE), List.of("queue", "demand"));
    }
    return changed;
  }

  private int record(List<UUID> rows, UUID retiredId, UUID survivorId, String subject) {
    return MaterialReferenceParticipant.recordMoved(
        auditRecorder,
        AuditEventType.JOB_ORDER_ITEM_CATALOGUE_REASSIGNED,
        rows,
        retiredId,
        survivorId,
        subject);
  }
}
