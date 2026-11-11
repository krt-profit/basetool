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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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
  private final ObjectProvider<JobOrderBankPort> bankPort;

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
    UUID bankAccountId = dto.bankAccountId();
    if (bankAccountId != null) {
      if (selection.kind() != JobOrderMoneySelection.Kind.SELF) {
        throw JobOrderLinkedPaymentTerms.invalid();
      }
      identityLock.lockAccounts(actor);
      return editor.editMoney(orderId, order -> designateBank(order, bankAccountId));
    }
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

  private JobOrderEditor.MoneyEdit<JobOrderMoneyRecipientView> designateBank(
      JobOrder order, UUID accountId) {
    authorizeChange(order);
    if (!bankAccountVisible(accountId)) {
      throw new BadRequestException("orders.money.bank.accountUnavailable");
    }
    var existing =
        recipients.findAllByOrderIdOrderById(order.getId()).stream()
            .filter(row -> row.isActive() && accountId.equals(row.getBankAccountId()))
            .findFirst();
    if (existing.isPresent()) {
      return new JobOrderEditor.MoneyEdit<>(
          view(existing.orElseThrow()), JobOrderEdit.unchanged(order));
    }
    JobOrderMoneyRecipient saved =
        recipients.save(new JobOrderMoneyRecipient(order.getId(), accountId));
    effects.designationChanged(order, false);
    return new JobOrderEditor.MoneyEdit<>(view(saved), designationAudit(order, saved));
  }

  private boolean bankAccountVisible(UUID accountId) {
    JobOrderBankPort port = bankPort.getIfUnique();
    return port != null && port.canSeeAccount(accountId);
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
        return new JobOrderEditor.MoneyEdit<>(view(prior), JobOrderEdit.unchanged(order));
      }
    }
    JobOrderMoneyRecipient saved = recipients.save(candidate);
    effects.designationChanged(order, candidateParty.accountId() != null);
    return new JobOrderEditor.MoneyEdit<>(view(saved), designationAudit(order, saved));
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

  UUID supersede(JobOrderMoneyRecipient original, JobOrderMoneyRecipient replacement) {
    UUID replacementId = Objects.requireNonNull(recipients.save(replacement).getId());
    original.supersede(replacementId);
    recipients.redirectCorrections(
        original.getOrderId(), Objects.requireNonNull(original.getId()), replacementId);
    return replacementId;
  }

  void refreshDesignationsForCorrectedHolder(UUID orderId, UUID holderId) {
    List<JobOrderMoneyRecipient> originals =
        recipients.findAllByOrderIdOrderById(orderId).stream()
            .filter(row -> !row.isSuperseded() && row.getHolderId() != null)
            .filter(row -> holderId.equals(party(row).holderId()))
            .toList();
    var selection =
        new JobOrderMoneySelection(JobOrderMoneySelection.Kind.EXISTING, holderId, null);
    for (JobOrderMoneyRecipient original : originals) {
      var replacement = new JobOrderMoneyRecipient(orderId, selection);
      if (!original.isActive()) {
        replacement.remove();
      }
      supersede(original, replacement);
    }
  }

  Map<UUID, UUID> correctedRecipientAliases(UUID orderId) {
    return recipients.findAllByOrderIdOrderById(orderId).stream()
        .filter(JobOrderMoneyRecipient::isSuperseded)
        .collect(
            Collectors.toUnmodifiableMap(
                row -> Objects.requireNonNull(row.getId()),
                row -> Objects.requireNonNull(row.getCorrectedIntoId())));
  }

  List<JobOrderMoneyRecipientView> views(UUID orderId) {
    List<JobOrderMoneyRecipient> designations = recipients.findAllByOrderIdOrderById(orderId);
    Map<UUID, JobOrderMoneyRecipient> byId =
        designations.stream()
            .collect(
                Collectors.toMap(row -> Objects.requireNonNull(row.getId()), Function.identity()));
    Map<UUID, String> names =
        holderChoices.namesOf(
            designations.stream()
                .flatMap(row -> Stream.of(row.getUserId(), row.getCorrectionActorId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    return designations.stream()
        .map(row -> view(row, id -> Objects.requireNonNull(byId.get(id)), names::get))
        .toList();
  }

  Party party(JobOrderMoneyRecipient recipient) {
    return party(currentRecipient(recipient, this::findRecipient), holderChoices::nameOf);
  }

  private Party party(JobOrderMoneyRecipient recipient, Function<UUID, @Nullable String> names) {
    UUID id = recipient.getHolderId();
    if (id == null) {
      return new Party(null, recipient.getUserId(), recordedName(recipient, names));
    }
    JobOrderMoneyHolder holder = holders.findById(id).orElseThrow();
    UUID canonical = holder.getMergedIntoId();
    if (canonical != null) {
      holder = holders.findById(canonical).orElseThrow();
    }
    return new Party(holder.getId(), holder.getUserId(), holderChoices.nameOf(holder));
  }

  JobOrderMoneyRecipientView view(JobOrderMoneyRecipient recipient) {
    return view(recipient, this::findRecipient, holderChoices::nameOf);
  }

  private JobOrderMoneyRecipientView view(
      JobOrderMoneyRecipient recipient,
      Function<UUID, JobOrderMoneyRecipient> find,
      Function<UUID, @Nullable String> names) {
    Party party = party(currentRecipient(recipient, find), names);
    UUID previousId = recipient.getCorrectsId();
    UUID actor = recipient.getCorrectionActorId();
    return new JobOrderMoneyRecipientView(
        Objects.requireNonNull(recipient.getId()),
        party.accountId(),
        party.name(),
        recipient.isActive(),
        party.holderId(),
        recipient.hasDirectIdentity() && !recipient.isSuperseded(),
        previousId,
        previousId == null ? null : recordedName(find.apply(previousId), names),
        previousId == null ? null : recordedName(recipient, names),
        recipient.getCorrectedAt(),
        actor == null ? null : names.apply(actor),
        recipient.getBankAccountId() != null,
        recipient.getBankAccountId() != null && bankAccountVisible(recipient.getBankAccountId())
            ? recipient.getBankAccountId()
            : null);
  }

  private static JobOrderMoneyRecipient currentRecipient(
      JobOrderMoneyRecipient recipient, Function<UUID, JobOrderMoneyRecipient> find) {
    UUID replacement = recipient.getCorrectedIntoId();
    return replacement == null ? recipient : find.apply(replacement);
  }

  private JobOrderMoneyRecipient findRecipient(UUID id) {
    return recipients.findById(id).orElseThrow();
  }

  private static @Nullable String recordedName(
      JobOrderMoneyRecipient recipient, Function<UUID, @Nullable String> names) {
    UUID account = recipient.getUserId();
    return account == null ? recipient.getAccountlessName() : names.apply(account);
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
