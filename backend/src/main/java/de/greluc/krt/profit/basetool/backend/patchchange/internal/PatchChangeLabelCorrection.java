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
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrects the label of a triggered patch change (REQ-PATCH-008). The live time and the effects
 * stay as they ran (ADR-0381); the label keeps its place between its neighbours.
 */
@Service
@RequiredArgsConstructor
public class PatchChangeLabelCorrection {

  private final PatchChangeRepository repository;
  private final PatchChangeHistory history;
  private final PatchChangeLiveSync liveSync;
  private final AuditRecorder auditRecorder;

  /**
   * Corrects a patch change's label.
   *
   * @param id the patch change
   * @param request the corrected label and the version the admin last saw
   * @return the corrected patch change as its history row
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no patch change has
   *     that id
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the label is
   *     malformed, not higher than the previous patch change's or not lower than the next one's
   */
  @Transactional
  public @NotNull PatchChangeDto correct(
      @NotNull UUID id, @NotNull CorrectPatchChangeLabelRequest request) {
    PatchLabel corrected = PatchLabel.parse(request.label());
    repository.lockHistory();
    PatchChange record =
        Entities.require(repository.findWithDetailsById(id), "error.patch_change.not_found");
    OptimisticLock.check(record.getVersion(), request.version(), PatchChange.class, id);
    String previousLabel = record.getLabel();
    if (previousLabel.equals(corrected.value())) {
      return history.toDto(record);
    }
    PatchChangeRules.requireLabelAfter(
        corrected,
        repository.findFirstByLiveAtLessThanOrderByLiveAtDesc(record.getLiveAt()).orElse(null));
    PatchChangeRules.requireLabelBefore(
        corrected,
        repository.findFirstByLiveAtGreaterThanOrderByLiveAtAsc(record.getLiveAt()).orElse(null));
    record.correctLabel(corrected);
    repository.flush();
    auditRecorder.record(
        AuditEventType.PATCH_CHANGE_LABEL_CORRECTED,
        id,
        corrected.value(),
        null,
        AuditDetails.of("from", previousLabel).with("to", corrected.value()));
    liveSync.historyChanged();
    return history.toDto(record);
  }
}
