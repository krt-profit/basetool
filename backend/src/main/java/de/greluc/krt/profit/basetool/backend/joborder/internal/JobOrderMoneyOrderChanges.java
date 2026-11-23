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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientNames;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderInformationCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderReadCheck;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderTargetCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies independent target and contextual information changes for aUEC orders, for the web and
 * for a caller that passes its own writer rule, read check and actor (ADR-0408).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneyOrderChanges
    implements JobOrderTargetCommand, JobOrderInformationCommand {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderWriters writers;
  private final JobOrderMoneyIdentityLock identityLock;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyTargetChangeRepository history;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderService lifecycle;
  private final JobOrderMoneyEffects effects;
  private final JobOrderClientNames clientNames;
  private final JobOrderClientWriteRules clientRules;

  /**
   * Records an agreed new target for the web caller and reconciles completion in the same
   * transaction.
   *
   * @param orderId the aUEC order
   * @param dto the amount and its observed target revision
   */
  public void target(UUID orderId, UpdateJobOrderMoneyTargetDto dto) {
    writeTarget(orderId, WholeAuec.positive(dto.target()), dto.version(), access.webCaller());
  }

  @Override
  public void changeTarget(
      @NotNull UUID orderId,
      @NotNull BigDecimal target,
      long targetVersion,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderReadCheck read,
      @NotNull JobOrderActor actor) {
    writeTarget(
        orderId,
        WholeAuec.positive(target.toPlainString()),
        targetVersion,
        new JobOrderCaller(rule, read, actor));
  }

  /** The one target command, under the account and order locks (ADR-0408). */
  private void writeTarget(
      UUID orderId, BigDecimal requested, @Nullable Long version, JobOrderCaller caller) {
    UUID member = access.memberOf(caller.actor());
    identityLock.lockAccounts(member);
    editor.editMoney(
        orderId,
        caller.read(),
        order -> {
          requireWriter(order, member, caller);
          return changesState(order.getStatus(), requested, ledger.delivered(orderId));
        },
        (order, queueLocked) ->
            targetLocked(order, requested, version, member, caller, queueLocked));
  }

  private JobOrderEditor.MoneyEdit<JobOrder> targetLocked(
      JobOrder order,
      BigDecimal requested,
      @Nullable Long version,
      UUID member,
      JobOrderCaller caller,
      boolean queueLocked) {
    final JobOrderWriters.Scope scope = requireWriter(order, member, caller);
    JobOrderMoneyTarget target = targets.findById(order.getId()).orElseThrow();
    OptimisticLock.check(target.getVersion(), version, JobOrderMoneyTarget.class, order.getId());
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
    history.save(
        new JobOrderMoneyTargetChange(
            order.getId(), member, caller.actor().clientId(), prior, requested));
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
      audit = audit.notifying(List.of(needChanged(order, member, scope, caller.actor())));
    }
    return new JobOrderEditor.MoneyEdit<>(order, audit);
  }

  /**
   * Edits the contact and comment for the web caller without changing the target or another edit
   * scope.
   *
   * <p>Both observed revisions must be current; each part raises only its own revision, and only
   * when its value changes.
   *
   * @param orderId the aUEC order
   * @param dto the information fields and their observed revisions
   */
  public void information(UUID orderId, UpdateJobOrderMoneyInformationDto dto) {
    JobOrderCaller caller = access.webCaller();
    UUID member = access.memberOf(caller.actor());
    identityLock.lockAccounts(member);
    editor.editMoney(
        orderId,
        caller.read(),
        _ -> false,
        (order, _) -> {
          final JobOrderWriters.Scope scope = requireWriter(order, member, caller);
          OptimisticLock.check(
              order.getMoneyInformationVersion(), dto.version(), JobOrder.class, orderId);
          JobOrderComment.check(order, dto.commentVersion());
          boolean contactChanged = replaceContact(order, dto.handle());
          boolean commentChanged = JobOrderComment.replace(order, dto.comment(), caller.actor());
          if (!contactChanged && !commentChanged) {
            return new JobOrderEditor.MoneyEdit<>(order, JobOrderEdit.unchanged(order));
          }
          return informationChanged(
              order,
              JobOrderEdit.audited(
                  order, AuditEventType.JOB_ORDER_UPDATED, AuditDetails.of("information", true)),
              member,
              scope,
              caller.actor());
        });
  }

  @Override
  public void changeComment(
      @NotNull UUID orderId,
      @Nullable String comment,
      long commentVersion,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderReadCheck read,
      @NotNull JobOrderActor actor) {
    JobOrderCaller caller = new JobOrderCaller(rule, read, actor);
    UUID member = access.memberOf(actor);
    identityLock.lockAccounts(member);
    editor.editMoney(
        orderId,
        clientRules.checks(
            caller,
            new JobOrderClientWriteRules.Write(
                JobOrderWriters.Scope::writesEitherSide,
                JobOrder::getCommentVersion,
                commentVersion,
                JobOrderMoneyOrderChanges::openForEdits)),
        _ -> false,
        (order, _) -> commentLocked(order, comment, member, caller));
  }

  /**
   * Replaces the comment of the checked, locked order for a client's write and audits it with the
   * client's own type and no details, since the text is never recorded (REQ-OXCH-008).
   */
  private JobOrderEditor.MoneyEdit<JobOrder> commentLocked(
      JobOrder order, @Nullable String comment, UUID member, JobOrderCaller caller) {
    access.requireActive(member);
    if (!JobOrderComment.replace(order, comment, caller.actor())) {
      return new JobOrderEditor.MoneyEdit<>(order, JobOrderEdit.unchanged(order));
    }
    return informationChanged(
        order,
        JobOrderEdit.audited(order, AuditEventType.JOB_ORDER_COMMENT_CHANGED_VIA_CLIENT, null),
        member,
        writers.resolve(caller.rule(), caller.actor()),
        caller.actor());
  }

  /**
   * Completes an information write that changed the order: the live update of its section, then the
   * audit with the need-change notice.
   */
  private JobOrderEditor.MoneyEdit<JobOrder> informationChanged(
      JobOrder order,
      JobOrderEdit audit,
      UUID member,
      JobOrderWriters.Scope scope,
      JobOrderActor actor) {
    effects.informationChanged(order);
    return new JobOrderEditor.MoneyEdit<>(
        order, audit.notifying(List.of(needChanged(order, member, scope, actor))));
  }

  /**
   * Requires an active member who writes either side of the order under the caller's rule, and an
   * order that is not cancelled.
   */
  private JobOrderWriters.Scope requireWriter(JobOrder order, UUID member, JobOrderCaller caller) {
    access.requireActive(member);
    JobOrderWriters.Scope scope = writers.resolve(caller.rule(), caller.actor());
    access.requireUnitWriter(order, scope);
    requireOpenForEdits(order);
    return scope;
  }

  /**
   * Announces the change from the responsible side when the actor writes it there, naming the
   * application a client's write came through (REQ-ORDERS-078).
   */
  private NotificationEvent needChanged(
      JobOrder order, UUID member, JobOrderWriters.Scope scope, JobOrderActor actor) {
    return JobOrderLifecycleNotices.needChanged(
        order, member, scope.writesResponsible(order), clientNames.appOf(actor));
  }

  private static boolean replaceContact(JobOrder order, String handle) {
    String contact = handle.strip();
    if (Objects.equals(order.getHandle(), contact)) {
      return false;
    }
    order.setHandle(contact);
    order.setMoneyInformationVersion(Math.incrementExact(order.getMoneyInformationVersion()));
    return true;
  }

  private static void requireOpenForEdits(JobOrder order) {
    if (!openForEdits(order.getStatus())) {
      throw new BusinessConflictException("orders.money.movement.inactive");
    }
  }

  /** Tells whether an order in the status takes target and information edits: all but cancelled. */
  private static boolean openForEdits(JobOrderStatus status) {
    return status != JobOrderStatus.CANCELLED;
  }

  private static boolean changesState(
      JobOrderStatus status, BigDecimal target, BigDecimal delivered) {
    boolean met = delivered.compareTo(target) >= 0;
    return (status.isActive() && met) || (status == JobOrderStatus.COMPLETED && !met);
  }
}
