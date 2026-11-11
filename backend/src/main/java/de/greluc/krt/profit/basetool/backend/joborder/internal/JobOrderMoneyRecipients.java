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
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manages deliberate requester designations and resolves their current identities. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneyRecipients {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyRecipientRepository recipients;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyHolders holderChoices;
  private final JobOrderMoneyIdentityLock identityLock;
  private final JobOrderMoneyEffects effects;

  /**
   * Designates an eligible account, existing holder or accountless recipient.
   *
   * @param orderId the aUEC order
   * @param dto the deliberately selected recipient
   * @return the current designation
   */
  public JobOrderMoneyRecipientView designate(
      UUID orderId, DesignateJobOrderMoneyRecipientDto dto) {
    JobOrderMoneySelection selection = dto.selection();
    UUID actor = access.actorId();
    UUID account =
        switch (selection.kind()) {
          case EXISTING ->
              holders
                  .accountFor(orderId, Objects.requireNonNull(selection.reference()))
                  .orElse(null);
          case LINKED -> selection.reference();
          case ACCOUNTLESS -> null;
          case SELF -> throw new BadRequestException("orders.money.recipient.selection.invalid");
        };
    identityLock.lockAccounts(actor, account);
    return editor.editMoney(orderId, order -> designateLocked(order, selection, account));
  }

  private JobOrderEditor.MoneyEdit<JobOrderMoneyRecipientView> designateLocked(
      JobOrder order, JobOrderMoneySelection selection, @Nullable UUID observedAccount) {
    authorizeChange(order);
    if (selection.kind() != JobOrderMoneySelection.Kind.ACCOUNTLESS) {
      JobOrderMoneyHolder selected;
      try {
        selected = holderChoices.resolve(order.getId(), access.actorId(), selection);
      } catch (JobOrderMoneyHolders.UnavailableHolderException unavailable) {
        throw unavailable.previewProblem();
      }
      if (!Objects.equals(observedAccount, selected.getUserId())) {
        throw new BusinessConflictException("orders.money.holder.linkChanged");
      }
    }
    JobOrderMoneyRecipient candidate = new JobOrderMoneyRecipient(order.getId(), selection);
    Party candidateParty = party(candidate);
    if (!candidateParty.usable()) {
      throw new BadRequestException("orders.money.recipient.selection.invalid");
    }
    for (JobOrderMoneyRecipient prior : recipients.findAllByOrderIdOrderById(order.getId())) {
      Party existing = party(prior);
      if (prior.isActive() && existing.sameKnownPerson(candidateParty)) {
        return new JobOrderEditor.MoneyEdit<>(view(prior, existing), JobOrderEdit.unchanged(order));
      }
    }
    JobOrderMoneyRecipient saved = recipients.save(candidate);
    effects.designationChanged(order, candidateParty.accountId() != null);
    return new JobOrderEditor.MoneyEdit<>(
        view(saved, candidateParty), designationAudit(order, saved));
  }

  /**
   * Ends a requester designation while retaining its historical movement references.
   *
   * @param orderId the aUEC order
   * @param recipientId the existing designation
   */
  public void retire(UUID orderId, UUID recipientId) {
    identityLock.lockAccounts(access.actorId(), accountFor(orderId, recipientId));
    editor.editMoney(
        orderId,
        order -> {
          authorizeChange(order);
          JobOrderMoneyRecipient recipient = require(orderId, recipientId);
          if (!recipient.isActive()) {
            return new JobOrderEditor.MoneyEdit<>(order, JobOrderEdit.unchanged(order));
          }
          recipient.remove();
          effects.designationChanged(order, party(recipient).accountId() != null);
          return new JobOrderEditor.MoneyEdit<>(order, designationAudit(order, recipient));
        });
  }

  JobOrderMoneyRecipient require(UUID orderId, UUID id) {
    return recipients
        .findByOrderIdAndId(orderId, id)
        .orElseThrow(() -> new BadRequestException("orders.money.recipient.notFound"));
  }

  @Nullable
  UUID accountFor(UUID orderId, UUID id) {
    return recipients.accountFor(orderId, id).orElse(null);
  }

  List<JobOrderMoneyRecipientView> views(UUID orderId) {
    return recipients.findAllByOrderIdOrderById(orderId).stream()
        .map(recipient -> view(recipient, party(recipient)))
        .toList();
  }

  Party party(JobOrderMoneyRecipient recipient) {
    UUID id = recipient.getHolderId();
    if (id == null) {
      UUID userId = recipient.getUserId();
      return new Party(
          null,
          userId,
          userId == null ? recipient.getAccountlessName() : holderChoices.nameOf(userId));
    }
    JobOrderMoneyHolder holder = holders.findById(id).orElseThrow();
    UUID canonical = holder.getMergedIntoId();
    if (canonical != null) {
      holder = holders.findById(canonical).orElseThrow();
    }
    return new Party(holder.getId(), holder.getUserId(), holderChoices.nameOf(holder));
  }

  private static JobOrderMoneyRecipientView view(JobOrderMoneyRecipient recipient, Party party) {
    return new JobOrderMoneyRecipientView(
        Objects.requireNonNull(recipient.getId()),
        party.accountId(),
        party.name(),
        recipient.isActive(),
        party.holderId());
  }

  private static JobOrderEdit designationAudit(JobOrder order, JobOrderMoneyRecipient recipient) {
    return JobOrderEdit.audited(
        order,
        AuditEventType.JOB_ORDER_MONEY_RECIPIENT_CHANGED,
        AuditDetails.of("recipientId", Objects.requireNonNull(recipient.getId()))
            .with("active", recipient.isActive()));
  }

  private void authorizeChange(JobOrder order) {
    access.requireActiveActor();
    access.requireUnitWriter(order);
    if (!order.getStatus().isActive()) {
      throw new BusinessConflictException("orders.money.movement.inactive");
    }
  }

  record Party(@Nullable UUID holderId, @Nullable UUID accountId, @Nullable String name) {

    boolean usable() {
      return name != null && !HandleAnonymisation.isReserved(name);
    }

    boolean sameKnownPerson(Party other) {
      return (holderId != null && holderId.equals(other.holderId))
          || (accountId != null && accountId.equals(other.accountId));
    }
  }
}
