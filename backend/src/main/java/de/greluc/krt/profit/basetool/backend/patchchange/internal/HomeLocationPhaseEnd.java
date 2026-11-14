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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocation;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends an open home-location phase early (REQ-PATCH-024): the gate and the lock end for every
 * member who has not chosen, and nothing more relocates in it; the open Lager and hangar pages
 * refresh their markers after the commit. The label and its version stay as they are, so a
 * concurrent label correction never collides with the end.
 */
@Service
@RequiredArgsConstructor
public class HomeLocationPhaseEnd {

  private final PatchChangeRepository repository;
  private final HomeLocationPhases phases;
  private final PatchChangeHistory history;
  private final PatchChangeLiveSync liveSync;
  private final AuditRecorder auditRecorder;
  private final AuthHelperService authHelper;
  private final StockRelocation stockRelocation;
  private final ShipRelocation shipRelocation;

  /**
   * Ends a patch change's open phase.
   *
   * @param id the patch change
   * @return the patch change as its history row
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no patch change has
   *     that id
   * @throws BusinessConflictException {@code error.patch_change.phase_not_open} when its phase is
   *     not the open one
   */
  @Transactional
  public @NotNull PatchChangeDto end(@NotNull UUID id) {
    repository.lockHistory();
    PatchChange record =
        Entities.require(repository.findWithDetailsById(id), "error.patch_change.not_found");
    HomeLocationPhase phase =
        repository
            .findFirstByHomeLocationPromptTrueOrderByLiveAtDesc()
            .filter(latest -> latest.getId().equals(id) && latest.getPhaseEndedAt() == null)
            .map(HomeLocationPhase::of)
            .orElseThrow(HomeLocationPhaseEnd::notOpen);
    List<UUID> pending = phases.allPending(phase);
    if (repository.endPhase(id, Instant.now(), authHelper.currentUserId().orElse(null)) == 0) {
      throw notOpen();
    }
    auditRecorder.record(
        AuditEventType.PATCH_CHANGE_PHASE_ENDED,
        id,
        record.getLabel(),
        null,
        AuditDetails.of("pending", pending.size()));
    liveSync.historyChanged();
    stockRelocation.announceLockChange();
    shipRelocation.announceLockChange(pending);
    return history.toDto(
        Entities.require(repository.findWithDetailsById(id), "error.patch_change.not_found"));
  }

  /**
   * The refusal of a phase that is not the open one.
   *
   * @return the conflict
   */
  private static @NotNull BusinessConflictException notOpen() {
    return new BusinessConflictException("error.patch_change.phase_not_open");
  }
}
