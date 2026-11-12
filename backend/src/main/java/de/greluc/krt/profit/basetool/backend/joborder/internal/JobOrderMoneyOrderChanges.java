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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies independent target and contextual information changes for aUEC orders. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneyOrderChanges {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyIdentityLock identityLock;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyTargetChangeRepository history;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderService lifecycle;
  private final JobOrderMoneyEffects effects;

  /**
   * Records an agreed new target and reconciles completion in the same transaction.
   *
   * @param orderId the aUEC order
   * @param dto the amount and its observed target revision
   */
  public void target(UUID orderId, UpdateJobOrderMoneyTargetDto dto) {
    BigDecimal requested = WholeAuec.positive(dto.target());
    UUID actor = access.actorId();
    identityLock.lockAccounts(actor);
    editor.editMoney(
        orderId,
        order -> {
          access.requireActiveActor();
          access.requireUnitWriter(order);
          requireOpenForEdits(order);
          return changesState(order.getStatus(), requested, ledger.delivered(orderId));
        },
        (order, queueLocked) -> targetLocked(order, dto, actor, requested, queueLocked));
  }

  private JobOrderEditor.MoneyEdit<JobOrder> targetLocked(
      JobOrder order,
      UpdateJobOrderMoneyTargetDto dto,
      UUID actor,
      BigDecimal requested,
      boolean queueLocked) {
    access.requireActiveActor();
    access.requireUnitWriter(order);
    requireOpenForEdits(order);
    JobOrderMoneyTarget target = targets.findById(order.getId()).orElseThrow();
    OptimisticLock.check(
        target.getVersion(), dto.version(), JobOrderMoneyTarget.class, order.getId());
    BigDecimal prior = target.getAmount();
    if (prior.compareTo(requested) == 0) {
      return new JobOrderEditor.MoneyEdit<>(order, JobOrderEdit.unchanged(order));
    }
    BigDecimal delivered = ledger.delivered(order.getId());
    boolean lifecycleChanged = changesState(order.getStatus(), requested, delivered);
    if (lifecycleChanged && !queueLocked) {
      throw new BusinessConflictException("orders.money.target.basisChanged");
    }
    target.replace(requested);
    history.save(new JobOrderMoneyTargetChange(order.getId(), actor, prior, requested));
    if (lifecycleChanged) {
      if (order.getStatus() == JobOrderStatus.COMPLETED) {
        lifecycle.reopenUnderfulfilledMoneyOrder(order);
      } else {
        lifecycle.completeJobOrderWithinTransaction(order);
      }
    }
    effects.targetChanged(order);
    JobOrderEdit audit =
        JobOrderEdit.audited(
            order,
            AuditEventType.JOB_ORDER_MONEY_TARGET_CHANGED,
            AuditDetails.of("previousTarget", prior).with("target", requested));
    if (!lifecycleChanged) {
      audit =
          audit.notifying(
              List.of(
                  JobOrderLifecycleNotices.needChanged(order, actor, access.responsible(order))));
    }
    return new JobOrderEditor.MoneyEdit<>(order, audit);
  }

  /**
   * Edits the contact and comment without changing the target or another edit scope.
   *
   * @param orderId the aUEC order
   * @param dto the information fields and their observed revision
   */
  public void information(UUID orderId, UpdateJobOrderMoneyInformationDto dto) {
    UUID actor = access.actorId();
    identityLock.lockAccounts(actor);
    editor.editMoney(
        orderId,
        order -> {
          access.requireActiveActor();
          access.requireUnitWriter(order);
          requireOpenForEdits(order);
          OptimisticLock.check(
              order.getMoneyInformationVersion(), dto.version(), JobOrder.class, orderId);
          String handle = dto.handle().strip();
          String comment = StringNormalization.trimToNull(dto.comment());
          if (Objects.equals(order.getHandle(), handle)
              && Objects.equals(order.getComment(), comment)) {
            return new JobOrderEditor.MoneyEdit<>(order, JobOrderEdit.unchanged(order));
          }
          order.setHandle(handle);
          order.setComment(comment);
          order.setMoneyInformationVersion(Math.incrementExact(order.getMoneyInformationVersion()));
          effects.informationChanged(order);
          return new JobOrderEditor.MoneyEdit<>(
              order,
              JobOrderEdit.audited(
                      order, AuditEventType.JOB_ORDER_UPDATED, AuditDetails.of("information", true))
                  .notifying(
                      List.of(
                          JobOrderLifecycleNotices.needChanged(
                              order, actor, access.responsible(order)))));
        });
  }

  private static void requireOpenForEdits(JobOrder order) {
    if (order.getStatus() == JobOrderStatus.CANCELLED) {
      throw new BusinessConflictException("orders.money.movement.inactive");
    }
  }

  private static boolean changesState(
      JobOrderStatus status, BigDecimal target, BigDecimal delivered) {
    boolean met = delivered.compareTo(target) >= 0;
    return (status.isActive() && met) || (status == JobOrderStatus.COMPLETED && !met);
  }
}
