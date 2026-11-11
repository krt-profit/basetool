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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyOperationRepository.PairedCorrectionCandidate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Finds reciprocal original legs that can be corrected from the current order history. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyPairedCorrectionChoices {

  private final JobOrderRepository orders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyAccess access;
  private final JobOrderResponsibilityGuard responsibility;

  Set<UUID> eligibleOriginals(JobOrder order, UUID actor, Set<UUID> readablePeerOrderIds) {
    if (readablePeerOrderIds.isEmpty()) {
      return Set.of();
    }
    var peers =
        orders.findAllById(readablePeerOrderIds).stream()
            .filter(peer -> peer.getType() == JobOrderType.AUEC)
            .toList();
    Set<UUID> valid = responsibility.validOrderIds(peers);
    if (valid.isEmpty()) {
      return Set.of();
    }
    Set<UUID> canCoordinate =
        peers.stream()
            .filter(peer -> valid.contains(peer.getId()) && access.responsible(peer))
            .map(JobOrder::getId)
            .collect(Collectors.toUnmodifiableSet());
    return operations.pairedCorrectionCandidates(order.getId(), valid).stream()
        .filter(candidate ->
            canCoordinate.contains(candidate.getOrderId()) || actor.equals(candidate.getUserId()))
        .map(PairedCorrectionCandidate::getOperationId)
        .collect(Collectors.toUnmodifiableSet());
  }
}
