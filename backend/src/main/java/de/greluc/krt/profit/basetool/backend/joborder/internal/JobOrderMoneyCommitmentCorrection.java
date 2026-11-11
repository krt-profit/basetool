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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Reconciles an original provision's credited amount against its own commitment occurrence. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyCommitmentCorrection {

  private final JobOrderMoneyCommitmentChangeRepository changes;
  private final JobOrderMoneyCommitmentRepository commitments;
  private final JobOrderMoneyCommitmentCredit creditAuthority;
  private final JobOrderMoneyCommitments history;

  void attachCredit(
      JobOrder order,
      JobOrderMoneyOperation original,
      JobOrderMoneyCorrectionEntry entry,
      @Nullable JobOrderMoneyCorrectionEntry prior,
      boolean removeAttribution) {
    var credit = changes.findByOrderIdAndOperationId(order.getId(), original.getOperationId());
    if (credit.isEmpty()) {
      if (removeAttribution) {
        throw new BadRequestException("orders.money.correction.attribution");
      }
      return;
    }
    var originalCredit = credit.orElseThrow();
    BigDecimal previouslyCredited =
        prior == null ? originalCredit.creditedAmount() : Objects.requireNonNull(prior.getCredit());
    boolean removed = removeAttribution || (prior != null && prior.isAttributionRemoved());
    BigDecimal revised =
        removed ? BigDecimal.ZERO : entry.getProvision().min(originalCredit.creditBasis());
    if (revised.compareTo(previouslyCredited) != 0 || removeAttribution) {
      creditAuthority.assertAttributionAuthority(order, originalCredit.getCommitmentId());
    }
    entry.attachCredit(
        originalCredit.getCommitmentId(), previouslyCredited, revised, removed, removeAttribution);
  }

  void authorizeReplay(JobOrder order, List<JobOrderMoneyCorrectionEntry> entries) {
    for (var entry : entries) {
      UUID commitmentId = entry.getCommitmentId();
      if (commitmentId != null
          && (entry.isRemoveAttributionRequested()
              || Objects.requireNonNull(entry.getPreviousCredit())
                      .compareTo(Objects.requireNonNull(entry.getCredit()))
                  != 0)) {
        creditAuthority.assertAttributionAuthority(order, commitmentId);
      }
    }
  }

  void apply(JobOrder order, UUID correctionId, List<JobOrderMoneyCorrectionEntry> entries) {
    Map<UUID, BigDecimal> restored = new HashMap<>();
    for (var entry : entries) {
      UUID occurrence = entry.getCommitmentId();
      if (occurrence == null) {
        continue;
      }
      BigDecimal previous = Objects.requireNonNull(entry.getPreviousCredit());
      BigDecimal revised = Objects.requireNonNull(entry.getCredit());
      if (previous.compareTo(revised) != 0 || entry.isRemoveAttributionRequested()) {
        restored.merge(occurrence, previous.subtract(revised), BigDecimal::add);
      }
    }
    restored.forEach((id, delta) -> {
      var commitment = commitments.findByIdAndOrderId(id, order.getId()).orElseThrow();
      BigDecimal before = commitment.getOutstanding();
      if (commitment.getEndedAt() == null) {
        BigDecimal after = before.add(delta);
        if (after.signum() <= 0) {
          commitment.end();
        } else {
          commitment.revise(after);
        }
      }
      history.recordCorrection(commitment, before, correctionId);
    });
  }
}
