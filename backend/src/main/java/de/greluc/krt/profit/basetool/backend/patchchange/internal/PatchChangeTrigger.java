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
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeWrites;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Previews and triggers a patch change (REQ-PATCH-005): the record and every enabled effect commit
 * in one transaction or not at all, and the exchange feed attributes every row the trigger writes
 * to the patch change (REQ-PATCH-007).
 */
@Service
@RequiredArgsConstructor
public class PatchChangeTrigger {

  private final PatchChangeRepository repository;
  private final PatchChangeEffects effects;
  private final PatchChangeHistory history;
  private final PatchChangeLiveSync liveSync;
  private final AuditRecorder auditRecorder;
  private final AuthHelperService authHelper;
  private final PlatformTransactionManager transactionManager;

  /**
   * Counts what a patch change would touch now, only from what its live time selects.
   *
   * @param request the live time and options under preview
   * @return the counts of every enabled effect
   * @throws BadRequestException when the live time lies after now or not after the previous patch
   *     change's
   */
  @Transactional(readOnly = true)
  public @NotNull PatchChangePreviewDto preview(@NotNull PatchChangePreviewRequest request) {
    Instant liveAt =
        PatchChangeRules.requireLiveAt(
            request.liveAt(),
            Instant.now(),
            repository.findFirstByOrderByLiveAtDesc().orElse(null));
    PatchCounts counts = effects.preview(new PatchChangePlan(liveAt, request.options()));
    return new PatchChangePreviewDto(liveAt, counts.toDtos());
  }

  /**
   * Records a patch change and runs every enabled effect in one transaction, for every org unit.
   *
   * @param request the label, kind, live time, options and the typed confirmation
   * @return the recorded patch change as its history row
   * @throws BadRequestException when the label is malformed or not higher than the previous one,
   *     the confirmation differs from the label, or the live time lies after now or not after the
   *     previous patch change's
   */
  public @NotNull PatchChangeDto trigger(@NotNull TriggerPatchChangeRequest request) {
    PatchLabel label = PatchLabel.parse(request.label());
    if (!label.value().equals(request.confirmation())) {
      throw new BadRequestException("error.patch_change.confirmation_mismatch");
    }
    UUID id = UUID.randomUUID();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    return PatchChangeWrites.runFor(
        id,
        () ->
            Objects.requireNonNull(
                transaction.execute(_ -> triggerWithinTransaction(id, label, request))));
  }

  /**
   * Checks the order rules under the history lock, then records the patch change and runs its
   * effects.
   *
   * @param id the id assigned before the transaction began
   * @param label the validated label
   * @param request the trigger
   * @return the recorded patch change as its history row
   */
  private @NotNull PatchChangeDto triggerWithinTransaction(
      @NotNull UUID id, @NotNull PatchLabel label, @NotNull TriggerPatchChangeRequest request) {
    repository.lockHistory();
    PatchChange previous = repository.findFirstByOrderByLiveAtDesc().orElse(null);
    Instant now = Instant.now();
    Instant liveAt = PatchChangeRules.requireLiveAt(request.liveAt(), now, previous);
    PatchChangeRules.requireLabelAfter(label, previous);
    PatchChangePlan plan = new PatchChangePlan(liveAt, request.options());
    PatchChange record =
        repository.save(
            new PatchChange(
                id, label, request.kind(), plan, now, authHelper.currentUserId().orElse(null)));
    PatchCounts counts = effects.apply(new PatchChangeRun(id, label.value(), plan));
    record.recordCounts(counts);
    repository.flush();
    auditRecorder.record(
        AuditEventType.PATCH_CHANGE_TRIGGERED, id, label.value(), null, details(record, counts));
    liveSync.historyChanged();
    return history.toDto(record);
  }

  /**
   * Builds the trigger's audit details: kind, live time, options and counts, never free text.
   *
   * @param record the recorded patch change
   * @param counts what the effects touched
   * @return the details
   */
  private static @NotNull AuditDetails details(
      @NotNull PatchChange record, @NotNull PatchCounts counts) {
    PatchChangeOptions options = record.options();
    AuditDetails details =
        AuditDetails.of("kind", record.getKind())
            .with("liveAt", record.getLiveAt())
            .with(
                "wipeGroups",
                options.wipeGroups().stream()
                    .sorted()
                    .map(Enum::name)
                    .collect(Collectors.joining("+")))
            .with("refinery", options.cancelRefineryOrders())
            .with("currency", options.currencyWipe())
            .with("homeLocation", options.homeLocationPrompt());
    counts.asMap().forEach((kind, value) -> details.with(kind.name(), value));
    return details;
  }
}
