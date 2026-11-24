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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCaller;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientNames;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientWriteRefusedException;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderInformationCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderTargetCommand;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies independent target and contextual information changes for aUEC orders, for the web and
 * for a caller that passes its own writer rule, read check and actor (ADR-0408). Both channels run
 * one target body and one information body under the order locks; each channel brings only its own
 * checks.
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
    BigDecimal requested = WholeAuec.positive(dto.target());
    Channel web = web();
    lockAccounts(web);
    editor.editMoney(
        orderId,
        web.checks(),
        order -> {
          web.writer().require(order);
          return changesState(order.getStatus(), requested, ledger.delivered(orderId));
        },
        (order, queueLocked) -> targetLocked(order, requested, dto.version(), web, queueLocked));
  }

  @Override
  public void changeTarget(
      @NotNull UUID orderId,
      @NotNull BigDecimal target,
      long targetVersion,
      @NotNull JobOrderCaller caller) {
    BigDecimal requested = WholeAuec.positive(target);
    Channel client =
        client(
            caller,
            new JobOrderClientWriteRules.Write(
                JobOrderWriters.Scope::writesEitherSide,
                order -> targets.versionFor(order.getId()).orElse(null),
                targetVersion,
                JobOrderStatus::isActive));
    lockAccounts(client);
    editor.editMoney(
        orderId,
        client.checks().andThen(order -> requireAboveHandover(order, requested)),
        _ -> false,
        (order, queueLocked) -> targetLocked(order, requested, targetVersion, client, queueLocked));
  }

  /**
   * Refuses a client's target at or below the order's current net handover, which would complete
   * the order (REQ-OXCH-007).
   *
   * @param order the loaded order
   * @param requested the new target
   * @throws JobOrderClientWriteRefusedException with {@code TARGET_TOO_LOW} for such a target
   */
  private void requireAboveHandover(JobOrder order, BigDecimal requested) {
    if (requested.compareTo(ledger.delivered(order.getId())) <= 0) {
      throw new JobOrderClientWriteRefusedException(
          JobOrderClientWriteRefusedException.Refusal.TARGET_TOO_LOW);
    }
  }

  /**
   * Replaces the target of the checked, locked order for either channel, records its history and
   * reconciles completion; a client's change records its own audit type (REQ-AUDIT-001, ADR-0407).
   */
  private JobOrderEditor.MoneyEdit<JobOrder> targetLocked(
      JobOrder order,
      BigDecimal requested,
      @Nullable Long version,
      Channel channel,
      boolean queueLocked) {
    final JobOrderWriters.Scope scope = channel.writer().require(order);
    final JobOrderActor actor = channel.caller().actor();
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
            order.getId(), access.memberOf(actor), actor.clientId(), prior, requested));
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
            actor.viaClient()
                ? AuditEventType.JOB_ORDER_TARGET_CHANGED_VIA_CLIENT
                : AuditEventType.JOB_ORDER_MONEY_TARGET_CHANGED,
            AuditDetails.of("previousTarget", prior).with("target", requested));
    if (!lifecycleChanged) {
      audit = audit.notifying(List.of(needChanged(order, scope, actor)));
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
    writeInformation(
        orderId,
        new Information(
            new Contact(dto.handle(), dto.version()), dto.comment(), dto.commentVersion()),
        web());
  }

  @Override
  public void changeComment(
      @NotNull UUID orderId,
      @Nullable String comment,
      long commentVersion,
      @NotNull JobOrderCaller caller) {
    writeInformation(
        orderId,
        new Information(null, comment, commentVersion),
        client(
            caller,
            new JobOrderClientWriteRules.Write(
                JobOrderWriters.Scope::writesEitherSide,
                JobOrder::getCommentVersion,
                commentVersion,
                JobOrderMoneyOrderChanges::openForEdits)));
  }

  /** Runs one information write of either channel under the account and order locks. */
  private void writeInformation(UUID orderId, Information change, Channel channel) {
    lockAccounts(channel);
    editor.editMoney(
        orderId,
        channel.checks(),
        _ -> false,
        (order, _) -> informationLocked(order, change, channel));
  }

  /**
   * Replaces the contact, when the change carries one, and the comment of the checked, locked
   * order. Each part checks and raises only its own revision; a client's change records its own
   * audit type and no details, since the text is never recorded (REQ-OXCH-008).
   */
  private JobOrderEditor.MoneyEdit<JobOrder> informationLocked(
      JobOrder order, Information change, Channel channel) {
    final JobOrderWriters.Scope scope = channel.writer().require(order);
    JobOrderActor actor = channel.caller().actor();
    Contact contact = change.contact();
    if (contact != null) {
      OptimisticLock.check(
          order.getMoneyInformationVersion(), contact.version(), JobOrder.class, order.getId());
    }
    JobOrderComment.check(order, change.commentVersion());
    boolean contactChanged = contact != null && replaceContact(order, contact.handle());
    boolean commentChanged = JobOrderComment.replace(order, change.comment(), actor);
    if (!contactChanged && !commentChanged) {
      return new JobOrderEditor.MoneyEdit<>(order, JobOrderEdit.unchanged(order));
    }
    effects.informationChanged(order);
    JobOrderEdit audit =
        actor.viaClient()
            ? JobOrderEdit.audited(order, AuditEventType.JOB_ORDER_COMMENT_CHANGED_VIA_CLIENT, null)
            : JobOrderEdit.audited(
                order, AuditEventType.JOB_ORDER_UPDATED, AuditDetails.of("information", true));
    return new JobOrderEditor.MoneyEdit<>(
        order, audit.notifying(List.of(needChanged(order, scope, actor))));
  }

  /**
   * The web channel: the editor's read checks of the contextual read, and under the order lock an
   * active member who writes either side of an order that is not cancelled.
   */
  private Channel web() {
    JobOrderCaller caller = access.webCaller();
    return new Channel(
        caller,
        editor.moneyChecks(caller.read()),
        order -> {
          JobOrderWriters.Scope scope = activeWriter(caller);
          access.requireUnitWriter(order, scope);
          requireOpenForEdits(order);
          return scope;
        });
  }

  /**
   * A client's channel: the shared checks of a client write decide the writer and the status before
   * and under the order lock, so the bodies add only the active account of the actor's member.
   */
  private Channel client(JobOrderCaller caller, JobOrderClientWriteRules.Write write) {
    return new Channel(caller, clientRules.checks(caller, write), _ -> activeWriter(caller));
  }

  /** Requires an active account of the caller's member and resolves the caller's writer units. */
  private JobOrderWriters.Scope activeWriter(JobOrderCaller caller) {
    access.requireActive(access.memberOf(caller.actor()));
    return writers.resolve(caller.rule(), caller.actor());
  }

  /** Serialises the write with the other money writes of the caller's member. */
  private void lockAccounts(Channel channel) {
    identityLock.lockAccounts(access.memberOf(channel.caller().actor()));
  }

  /**
   * Announces the change from the responsible side when the actor writes it there, naming the
   * application a client's write came through (REQ-ORDERS-078).
   */
  private NotificationEvent needChanged(
      JobOrder order, JobOrderWriters.Scope scope, JobOrderActor actor) {
    return JobOrderLifecycleNotices.needChanged(
        order, access.memberOf(actor), scope.writesResponsible(order), clientNames.appOf(actor));
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

  /**
   * One channel of a target or information write: its caller, the checks it runs on the loaded
   * order before and after the order row lock, and its writer decision on the locked order, which
   * the shared bodies run before they change it.
   *
   * @param caller the writer rule, read check and actor
   * @param checks the channel's checks in its order of refusals; each refusal is an exception
   * @param writer refuses a locked order the caller may not write, else names the caller's writer
   *     units
   */
  private record Channel(
      @NotNull JobOrderCaller caller,
      @NotNull Consumer<JobOrder> checks,
      @NotNull WriterCheck writer) {}

  /** A channel's writer decision on the locked order. */
  @FunctionalInterface
  private interface WriterCheck {

    /**
     * Refuses an order the caller may not write, else names the caller's writer units.
     *
     * @param order the loaded order
     * @return the caller's writer units
     */
    @NotNull
    JobOrderWriters.Scope require(@NotNull JobOrder order);
  }

  /**
   * One information write: the contact a web write replaces beside the comment, and the comment.
   *
   * @param contact the new contact and its observed revision, {@code null} to leave it untouched
   * @param comment the requested comment; blank clears it
   * @param commentVersion the observed comment revision; {@code null} is a conflict
   */
  private record Information(
      @Nullable Contact contact, @Nullable String comment, @Nullable Long commentVersion) {}

  /**
   * The contact part of a web information write.
   *
   * @param handle the requested contact, stripped before it is stored
   * @param version the observed contact revision; {@code null} is a conflict
   */
  private record Contact(@NotNull String handle, @Nullable Long version) {}
}
