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
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCorrectionPlans.Terms;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCorrectionPlans.UnavailableCorrection;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Revises connected financial facts at their current scoped versions. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneyCorrections {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyIdentityLock identities;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneyCorrectionPlans plans;
  private final JobOrderMoneyPairedCorrection paired;

  /**
   * Calculates the full effective correction without booking it.
   *
   * @param orderId the readable aUEC order
   * @param dto the original facts and their corrected amounts
   * @return the resulting effective financial figures
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public JobOrderMoneyCorrectionPreview preview(UUID orderId, CorrectJobOrderMoneyDto dto) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    if (queries.responsibilityInvalid(order)) {
      throw new AccessDeniedException("The order's responsibility is ineligible.");
    }
    var terms = JobOrderMoneyCorrectionPlans.terms(dto);
    UUID actor = access.requireActiveActor();
    var peer = paired.peerOrder(orderId, terms.keySet());
    try {
      return peer.isPresent()
          ? paired.preview(order, peer.orElseThrow(), dto, terms, actor)
          : plans.prepare(order, dto, terms, actor, null, true).preview(Map.of(), null);
    } catch (UnavailableCorrection unavailable) {
      throw new BusinessConflictException("orders.money.correction.insufficient");
    }
  }

  /**
   * Records a connected correction or returns its previously accepted outcome.
   *
   * @param orderId the aUEC order
   * @param dto the correction and its observed revisions
   * @return the accepted result or empty for a refused attempt
   */
  public Optional<JobOrderMoneyOutcome> record(UUID orderId, CorrectJobOrderMoneyDto dto) {
    UUID actor = access.actorId();
    queries.requireReadableMoneyOrder(orderId);
    var terms = JobOrderMoneyCorrectionPlans.terms(dto);
    var peer = paired.peerOrder(orderId, terms.keySet());
    if (peer.isPresent()) {
      return paired.record(orderId, peer.orElseThrow(), dto, terms, actor);
    }
    Set<UUID> accounts = operations.accountsAffectedByCorrection(orderId, terms.keySet());
    identities.lockAffectedAccounts(actor, accounts);
    return editor.editMoney(
        orderId,
        order -> plans.needsQueue(order, dto.operationId(), terms),
        (order, queueLocked) -> recordLocked(order, dto, terms, actor, accounts, queueLocked));
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> recordLocked(
      JobOrder order,
      CorrectJobOrderMoneyDto dto,
      Map<UUID, Terms> terms,
      UUID actor,
      Set<UUID> accounts,
      boolean queueLocked) {
    access.requireActiveActor();
    UUID orderId = order.getId();
    var accepted = operations.findByOrderIdAndOperationId(orderId, dto.operationId());
    if (accepted.isPresent()) {
      return plans.replay(order, accepted.orElseThrow(), terms, actor, null);
    }
    if (operations.isRefused(orderId, dto.operationId())) {
      return JobOrderMoneyCorrectionPlans.result(order, Optional.empty());
    }
    JobOrderMoneyCorrectionPlans.Plan plan;
    try {
      plan = plans.prepare(order, dto, terms, actor, null, true);
    } catch (ObjectOptimisticLockingFailureException | UnavailableCorrection invalidBasis) {
      return refuse(order, dto.operationId());
    }
    if (!accounts.equals(operations.accountsAffectedByCorrection(orderId, terms.keySet()))
        || (plan.lifecycleChanged() && !queueLocked)) {
      return refuse(order, dto.operationId());
    }
    return plans.post(order, dto.operationId(), actor, plan, null);
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> refuse(JobOrder order, UUID id) {
    operations.reserveRefusal(order.getId(), id);
    return JobOrderMoneyCorrectionPlans.result(order, Optional.empty());
  }
}
