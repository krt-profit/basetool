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

package de.greluc.krt.profit.basetool.backend.supply.internal;

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
 * Reports the materials supply needs and donations reference (REQ-P4K-075) and moves them onto the
 * material a merge keeps (REQ-DATA-046).
 */
@Component
@RequiredArgsConstructor
public class SupplyMaterialReferences implements MaterialReferenceParticipant {

  /** The materials among {@code :ids} a need or a donation references. */
  private static final String REFERENCED =
      "SELECT n.material.id FROM SupplyNeed n WHERE n.material.id IN :ids"
          + " UNION SELECT d.material.id FROM SupplyDonation d WHERE d.material.id IN :ids";

  private final EntityManager entityManager;
  private final SupplyMaterialReassignmentRepository reassignments;
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
    List<UUID> receivingUnits = reassignments.receivingUnits(retiredId);
    int changed =
        record(reassignments.needs(retiredId, survivorId), retiredId, survivorId, "supply_need")
            + record(
                reassignments.donations(retiredId, survivorId),
                retiredId,
                survivorId,
                "supply_donation");
    if (changed > 0) {
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.SUPPLY_NEEDS), List.of("needs", "mine", "stats"));
      for (UUID unit : receivingUnits) {
        liveSyncPublisher.publishAfterCommit(
            LiveSyncTopic.of(LiveSyncTopicClass.SUPPLY_DONATIONS, unit), List.of("donations"));
      }
    }
    return changed;
  }

  private int record(List<UUID> rows, UUID retiredId, UUID survivorId, String subject) {
    return MaterialReferenceParticipant.recordMoved(
        auditRecorder,
        AuditEventType.SUPPLY_CATALOGUE_REASSIGNED,
        rows,
        retiredId,
        survivorId,
        subject);
  }
}
