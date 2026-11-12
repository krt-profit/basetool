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
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Maintains deliberate identity links while leaving every original financial effect in place. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneyHolderIdentity {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyHolders choices;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyHolderChangeRepository history;
  private final JobOrderMoneyHolderAliases aliases;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyIdentityLock identityLock;
  private final JobOrderMoneyPrivacy privacy;
  private final JobOrderMoneyQueries queries;
  private final JobOrderResponsibilityGuard responsibility;

  /** Account links observed before acquiring the order lock. */
  private record AccountBasis(@Nullable UUID original, @Nullable UUID selected) {}

  /**
   * Applies the selected identity change to the observed holder revision.
   *
   * @param orderId the order whose current authority governs the change
   * @param holderId the original holder, which must still be current
   * @param dto the change and the applicable holder revisions
   */
  public void change(UUID orderId, UUID holderId, JobOrderMoneyHolderIdentityDto dto) {
    JobOrderMoneySelection selection = dto.selection(holderId);
    AccountBasis accounts =
        new AccountBasis(
            holders.accountFor(orderId, holderId).orElse(null),
            dto.targetHolderId() == null
                ? dto.userId()
                : holders.accountFor(orderId, dto.targetHolderId()).orElse(null));
    identityLock.lockAccounts(access.actorId(), accounts.original(), accounts.selected());
    editor.editMoney(orderId, order -> apply(order, holderId, dto, selection, accounts));
  }

  /**
   * Finds bounded active-account references for a responsible holder correction.
   *
   * @param orderId the order owning the picker
   * @param query optional account name fragment
   * @return account references that can be explicitly selected
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<JobOrderMoneyHolderCandidate> candidates(UUID orderId, @Nullable String query) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    responsibility.assertWritable(order);
    authorize(order);
    return choices.candidates(query);
  }

  private JobOrderEditor.MoneyEdit<JobOrder> apply(
      JobOrder order,
      UUID holderId,
      JobOrderMoneyHolderIdentityDto dto,
      JobOrderMoneySelection selection,
      AccountBasis accounts) {
    UUID actor = authorize(order);
    JobOrderMoneyHolder holder =
        current(order.getId(), holderId, dto.version(), accounts.original());
    JobOrderMoneyHolderChange change =
        switch (dto.kind()) {
          case NAME, LINK, CORRECT_LINK -> replaceIdentity(holder, dto.kind(), selection, actor);
          case MERGE ->
              merge(
                  holder,
                  current(
                      order.getId(),
                      Objects.requireNonNull(dto.targetHolderId()),
                      Objects.requireNonNull(dto.targetVersion()),
                      accounts.selected()),
                  actor);
        };
    if (change == null) {
      return new JobOrderEditor.MoneyEdit<>(order, JobOrderEdit.unchanged(order));
    }
    history.save(change);
    privacy.publishChanges(List.of(order.getId()));
    JobOrderEdit audit =
        JobOrderEdit.audited(
            order,
            AuditEventType.JOB_ORDER_MONEY_HOLDER_IDENTITY_CHANGED,
            AuditDetails.of("holderId", holderId).with("kind", dto.kind().name()));
    return new JobOrderEditor.MoneyEdit<>(order, audit);
  }

  private @Nullable JobOrderMoneyHolderChange replaceIdentity(
      JobOrderMoneyHolder holder,
      JobOrderMoneyHolderIdentityDto.Kind kind,
      JobOrderMoneySelection selection,
      UUID actor) {
    if ((holder.getUserId() != null)
        != (kind == JobOrderMoneyHolderIdentityDto.Kind.CORRECT_LINK)) {
      throw new BusinessConflictException("orders.money.holder.identity.correctionRequired");
    }
    UUID userId = selection.reference();
    String name = selection.name();
    if (Objects.equals(holder.getUserId(), userId)
        && Objects.equals(holder.getAccountlessName(), name)) {
      return null;
    }
    if (userId != null) {
      requireAvailableLink(holder.getOrderId(), userId);
    }
    JobOrderMoneyHolderChange change =
        new JobOrderMoneyHolderChange(holder, kind, actor, userId, name, null);
    holder.replaceIdentity(userId, name);
    if (kind == JobOrderMoneyHolderIdentityDto.Kind.CORRECT_LINK) {
      recipients.refreshDesignationsForCorrectedHolder(
          holder.getOrderId(), Objects.requireNonNull(holder.getId()));
    }
    return change;
  }

  private void requireAvailableLink(UUID orderId, UUID userId) {
    if (choices.activeUser(userId).isEmpty()) {
      throw new BusinessConflictException("orders.money.holder.accountInactive");
    }
    if (holders.findByOrderIdAndUserId(orderId, userId).isPresent()) {
      throw new BusinessConflictException("orders.money.holder.identity.mergeAccounts");
    }
  }

  private JobOrderMoneyHolderChange merge(
      JobOrderMoneyHolder source, JobOrderMoneyHolder survivor, UUID actor) {
    if (source.getUserId() != null) {
      throw new BusinessConflictException("orders.money.holder.identity.mergeAccounts");
    }
    JobOrderMoneyHolderChange change =
        new JobOrderMoneyHolderChange(
            source,
            JobOrderMoneyHolderIdentityDto.Kind.MERGE,
            actor,
            survivor.getUserId(),
            survivor.getAccountlessName(),
            survivor.getId());
    aliases.merge(List.of(new JobOrderMoneyHolderAliases.Pair(source, survivor)));
    return change;
  }

  private JobOrderMoneyHolder current(
      UUID orderId, UUID holderId, long version, @Nullable UUID observedAccount) {
    JobOrderMoneyHolder holder;
    try {
      holder = choices.requireCurrent(orderId, holderId);
    } catch (JobOrderMoneyHolders.UnavailableHolderException unavailable) {
      throw unavailable.previewProblem();
    }
    if (!Objects.equals(holder.getUserId(), observedAccount)) {
      throw new BusinessConflictException("orders.money.holder.linkChanged");
    }
    OptimisticLock.checkRequired(
        holder.getRevision(), version, JobOrderMoneyHolder.class, holderId);
    return holder;
  }

  private UUID authorize(JobOrder order) {
    UUID actor = access.requireActiveActor();
    if (!access.responsible(order)) {
      throw new AccessDeniedException("Only the current responsible writer may maintain holders.");
    }
    return actor;
  }
}
