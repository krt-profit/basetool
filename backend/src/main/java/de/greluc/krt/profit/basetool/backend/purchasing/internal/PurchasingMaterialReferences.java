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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

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
 * Reports the materials purchasing deals reference (REQ-P4K-075) and moves them onto the material a
 * merge keeps (REQ-DATA-046).
 */
@Component
@RequiredArgsConstructor
public class PurchasingMaterialReferences implements MaterialReferenceParticipant {

  /** The materials among {@code :ids} a deal references. */
  private static final String REFERENCED =
      "SELECT DISTINCT d.material.id FROM PurchasingDeal d WHERE d.material.id IN :ids";

  private final EntityManager entityManager;
  private final PurchasingMaterialReassignmentRepository reassignments;
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
    int changed =
        MaterialReferenceParticipant.recordMoved(
            auditRecorder,
            AuditEventType.PURCHASING_CATALOGUE_REASSIGNED,
            reassignments.deals(retiredId, survivorId),
            retiredId,
            survivorId,
            "purchasing_deal");
    if (changed > 0) {
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.PURCHASING_DEALS), List.of("deals"));
    }
    return changed;
  }
}
