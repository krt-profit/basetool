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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Corrects a mistaken direct designation without changing an accepted handover. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneyRecipientIdentity {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyRecipientRepository repository;
  private final JobOrderMoneyHolders holderChoices;
  private final JobOrderMoneyIdentityLock identityLock;
  private final JobOrderMoneyEffects effects;
  private final JobOrderMoneyQueries queries;
  private final JobOrderResponsibilityGuard responsibility;

  /**
   * Replaces the false designation and retains the original reference on accepted payments.
   *
   * @param orderId the aUEC order
   * @param recipientId the exact designation being corrected
   * @param dto the corrected account or accountless recipient
   * @return the corrected designation
   */
  public JobOrderMoneyRecipientView correct(
      UUID orderId, UUID recipientId, DesignateJobOrderMoneyRecipientDto dto) {
    JobOrderMoneySelection selection = dto.selection();
    if (selection.kind() != JobOrderMoneySelection.Kind.LINKED
        && selection.kind() != JobOrderMoneySelection.Kind.ACCOUNTLESS) {
      throw new BadRequestException("orders.money.recipient.identity.selection");
    }
    UUID originalAccount = recipients.accountFor(orderId, recipientId);
    identityLock.lockAccounts(access.actorId(), originalAccount, selection.reference());
    return editor.editMoney(
        orderId, order -> correctLocked(order, recipientId, originalAccount, selection));
  }

  /**
   * Offers active accounts to either current unit writer correcting a direct designation.
   *
   * @param orderId the order owning the designation
   * @param recipientId the direct designation
   * @param query optional account name fragment
   * @return the bounded list of eligible account references
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<JobOrderMoneyHolderCandidate> candidates(
      UUID orderId, UUID recipientId, @Nullable String query) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    responsibility.assertWritable(order);
    authorize(order);
    requireCorrectable(orderId, recipientId);
    return holderChoices.candidates(query);
  }

  private JobOrderEditor.MoneyEdit<JobOrderMoneyRecipientView> correctLocked(
      JobOrder order,
      UUID recipientId,
      @Nullable UUID observedAccount,
      JobOrderMoneySelection selection) {
    final UUID actor = authorize(order);
    JobOrderMoneyRecipient original = requireCorrectable(order.getId(), recipientId);
    if (!Objects.equals(observedAccount, original.getUserId())) {
      throw new BusinessConflictException("orders.money.recipient.identity.changed");
    }
    UUID account = selection.reference();
    if (account != null && holderChoices.activeUser(account).isEmpty()) {
      throw new BusinessConflictException("orders.money.holder.accountInactive");
    }
    if (Objects.equals(original.getUserId(), account)
        && Objects.equals(original.getAccountlessName(), selection.name())) {
      return new JobOrderEditor.MoneyEdit<>(
          recipients.view(original), JobOrderEdit.unchanged(order));
    }
    JobOrderMoneyRecipient replacement = new JobOrderMoneyRecipient(order.getId(), selection);
    boolean available = original.isActive() && !alreadyDesignated(order.getId(), replacement);
    replacement.recordCorrectionOf(original, actor, available);
    UUID replacementId = recipients.supersede(original, replacement);
    effects.designationChanged(order, true);
    JobOrderEdit edit =
        JobOrderEdit.audited(
            order,
            AuditEventType.JOB_ORDER_MONEY_RECIPIENT_CHANGED,
            AuditDetails.of("recipientId", replacementId).with("correctedFromId", recipientId));
    return new JobOrderEditor.MoneyEdit<>(recipients.view(replacement), edit);
  }

  private boolean alreadyDesignated(UUID orderId, JobOrderMoneyRecipient replacement) {
    JobOrderMoneyRecipients.Party party = recipients.party(replacement);
    return repository.findAllByOrderIdOrderById(orderId).stream()
        .filter(JobOrderMoneyRecipient::isActive)
        .map(recipients::party)
        .anyMatch(party::sameKnownPerson);
  }

  private JobOrderMoneyRecipient requireCorrectable(UUID orderId, UUID recipientId) {
    JobOrderMoneyRecipient recipient = recipients.require(orderId, recipientId);
    if (!recipient.hasDirectIdentity() || recipient.isSuperseded()) {
      throw new BusinessConflictException("orders.money.recipient.identity.changed");
    }
    return recipient;
  }

  private UUID authorize(JobOrder order) {
    UUID actor = access.requireActiveActor();
    access.requireUnitWriter(order);
    return actor;
  }
}
