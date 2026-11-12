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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preserves the Materialbörse's catalogue references and their audit trail when catalogue items
 * converge: an item offer or request named by a retired catalogue row moves to the survivor.
 */
@Component
@RequiredArgsConstructor
public class MaterialExchangeItemReassignment implements GameItemReassignmentParticipant {

  private final MaterialExchangeItemReassignmentRepository repository;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSyncPublisher;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int reassignGameItem(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    int changed = 0;
    changed +=
        record(
            repository.offers(retiredId, survivorId),
            retiredId,
            survivorId,
            "material_exchange_offer");
    changed +=
        record(
            repository.requests(retiredId, survivorId),
            retiredId,
            survivorId,
            "material_exchange_request");
    if (changed > 0) {
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), List.of("board"));
    }
    return changed;
  }

  private int record(List<UUID> rows, UUID retiredId, UUID survivorId, String subject) {
    for (UUID id : rows) {
      auditRecorder.record(
          AuditEventType.MARKET_CATALOGUE_REASSIGNED,
          id,
          null,
          null,
          "retiredItem=" + retiredId + ";survivorItem=" + survivorId + ";subject=" + subject);
    }
    return rows.size();
  }
}
