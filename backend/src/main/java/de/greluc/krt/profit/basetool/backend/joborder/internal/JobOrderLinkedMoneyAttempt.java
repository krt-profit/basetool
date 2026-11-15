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

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankEffects.Decision;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankEffects.Outcome;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Counterparty;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Retains frozen terms and the authoritative decision for one linked money attempt. */
@Entity
@Table(name = "job_order_linked_money_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class JobOrderLinkedMoneyAttempt {
  @Id private UUID id;
  @Version @Nullable private Long version;

  @Column(updatable = false)
  private UUID orderId;

  @Column(updatable = false)
  private UUID operationId;

  @Column(updatable = false)
  private UUID purposeRef;

  @Column(updatable = false)
  private long revision;

  private boolean active;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, length = 24)
  private JobOrderMoneyKind kind;

  @Nullable private UUID actorId;
  @Nullable private UUID decisionActorId;

  @Nullable
  @Column(updatable = false)
  private UUID holderId;

  @Nullable
  @Column(updatable = false)
  private UUID accountHoldingId;

  @Nullable
  @Column(updatable = false)
  private UUID destinationHolderId;

  @Nullable
  @Column(updatable = false)
  private UUID recipientId;

  @Nullable
  @Column(updatable = false)
  private UUID commitmentId;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, length = 16)
  @Nullable
  private JobOrderMoneySelection.Kind selectionKind;

  @Nullable
  @Column(updatable = false)
  private UUID selectedRef;

  @Nullable private UUID selectedUserId;

  @Nullable
  @Column(length = 200)
  private String selectedAccountlessName;

  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal amount;

  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal provision;

  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal rate;

  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal actualFee;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, length = 16)
  private JobOrderMoneyFeeFunding feeFunding;

  @Column(updatable = false, columnDefinition = "numeric")
  private BigDecimal sourceBinding;

  @Column(updatable = false)
  private boolean actualPayment;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, length = 16)
  private JobOrderBankSelection.Type bankType;

  @Nullable
  @Column(updatable = false)
  private UUID bankAccountId;

  @Nullable
  @Column(updatable = false)
  private UUID bankCustodianId;

  @Nullable
  @Column(updatable = false)
  private UUID bankTargetAccountId;

  @Nullable
  @Column(updatable = false)
  private UUID bankTargetCustodianId;

  @Nullable private UUID counterpartyUserId;

  @Nullable
  @Column(length = 200)
  private String counterpartyName;

  @Enumerated(EnumType.STRING)
  @Column(length = 16)
  private JobOrderLinkedMoneyState state;

  @Nullable private UUID requestId;
  @Nullable private UUID transactionId;
  @Nullable private UUID authorisationId;
  @Nullable private UUID reversalId;

  @Column(updatable = false)
  private Instant submittedAt;

  private Instant updatedAt;

  /** The patch change whose currency wipe cancelled the attempt; {@code null} for every other. */
  @Nullable private UUID cancelledByPatchChange;

  JobOrderLinkedMoneyAttempt(
      UUID orderId,
      UUID operationId,
      UUID actor,
      Terms terms,
      JobOrderBankSelection bank,
      @Nullable Counterparty depositor,
      @Nullable JobOrderLinkedMoneyAttempt previous) {
    this.id = UUID.randomUUID();
    this.purposeRef = previous == null ? id : previous.purposeRef;
    this.revision = previous == null ? 1 : Math.incrementExact(previous.revision);
    this.orderId = orderId;
    this.operationId = operationId;
    this.actorId = actor;
    this.active = true;
    this.kind = terms.kind();
    this.holderId = terms.holderId();
    this.accountHoldingId = terms.accountHoldingId();
    this.destinationHolderId = terms.destinationHolderId();
    this.recipientId = terms.recipientId();
    this.commitmentId = terms.commitmentId();
    var selection = terms.selection();
    this.selectionKind = selection == null ? null : selection.kind();
    this.selectedRef =
        selection != null && selection.kind() == JobOrderMoneySelection.Kind.EXISTING
            ? selection.reference()
            : null;
    this.selectedUserId =
        selection != null && selection.kind() == JobOrderMoneySelection.Kind.LINKED
            ? selection.reference()
            : null;
    this.selectedAccountlessName = selection == null ? null : selection.name();
    this.amount = terms.amount();
    this.provision = terms.provision();
    this.rate = terms.rate().rate();
    this.actualFee = terms.actualFee();
    this.feeFunding = terms.feeFunding();
    this.sourceBinding = terms.sourceBinding();
    this.actualPayment = terms.actualPayment();
    this.bankType = bank.type();
    this.bankAccountId = bank.accountId();
    this.bankCustodianId = bank.custodianId();
    this.bankTargetAccountId = bank.targetAccountId();
    this.bankTargetCustodianId = bank.targetCustodianId();
    this.counterpartyUserId = depositor == null ? null : depositor.userId();
    this.counterpartyName = depositor == null ? null : depositor.name();
    this.state = JobOrderLinkedMoneyState.SUBMITTING;
    this.submittedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    this.updatedAt = submittedAt;
  }

  JobOrderMoneySelection selection() {
    return new JobOrderMoneySelection(
        Objects.requireNonNull(selectionKind),
        selectionKind == JobOrderMoneySelection.Kind.LINKED ? selectedUserId : selectedRef,
        selectedAccountlessName);
  }

  RateSnapshot rateSnapshot() {
    return new RateSnapshot(rate);
  }

  BigDecimal orderFee() {
    return feeFunding == JobOrderMoneyFeeFunding.ORDER ? actualFee : BigDecimal.ZERO;
  }

  boolean matchesBank(JobOrderBankSelection bank) {
    return bankType == bank.type()
        && (accountHoldingId != null) == Boolean.TRUE.equals(bank.accountOnly())
        && Objects.equals(bankAccountId, bank.accountId())
        && Objects.equals(bankCustodianId, bank.custodianId())
        && Objects.equals(bankTargetAccountId, bank.targetAccountId())
        && Objects.equals(bankTargetCustodianId, bank.targetCustodianId())
        && actualPayment == JobOrderLinkedPaymentTerms.reported(bank)
        && (bankType != JobOrderBankSelection.Type.DEPOSIT
            || (feeFunding == JobOrderMoneyFeeFunding.ORDER)
                == Boolean.TRUE.equals(bank.orderFundedDepositFee()))
        && (kind != JobOrderMoneyKind.PROVISION
            || bankType != JobOrderBankSelection.Type.DEPOSIT
            || matchesDepositor(bank));
  }

  UUID personalHolderId() {
    return Objects.requireNonNull(holderId);
  }

  @Nullable
  Counterparty counterparty() {
    return counterpartyUserId == null && counterpartyName == null
        ? null
        : new Counterparty(counterpartyUserId, counterpartyName);
  }

  boolean matchesDepositor(JobOrderBankSelection bank) {
    var depositor = depositCounterpartyForReplacement();
    return Objects.equals(depositor == null ? null : depositor.userId(), bank.counterpartyUserId())
        && Objects.equals(
            depositor == null ? null : depositor.name(),
            JobOrderLinkedPaymentTerms.normalizedCounterparty(bank));
  }

  @Nullable
  Counterparty depositCounterpartyForReplacement() {
    return HandleAnonymisation.isAnonymised(counterpartyName) ? null : counterparty();
  }

  boolean permitsTerminalPosting() {
    return actualPayment
        || kind == JobOrderMoneyKind.REQUESTER_RETURN
        || kind == JobOrderMoneyKind.SETTLEMENT;
  }

  boolean replaceable() {
    return active
        && (state == JobOrderLinkedMoneyState.REJECTED
            || state == JobOrderLinkedMoneyState.CANCELLED);
  }

  void supersede() {
    if (!replaceable()) {
      throw conflict();
    }
    active = false;
  }

  boolean bindingActive() {
    return active
        && (state == JobOrderLinkedMoneyState.WAITING
            || state == JobOrderLinkedMoneyState.SUBMITTING
            || (actualPayment && replaceable()));
  }

  void decide(Decision decision, UUID actor) {
    if (!active) {
      throw conflict();
    }
    UUID reference = decision.id();
    switch (decision.kind()) {
      case null -> throw conflict();
      case WAITING -> {
        requireState(JobOrderLinkedMoneyState.SUBMITTING);
        requestId = reference;
        state = JobOrderLinkedMoneyState.WAITING;
      }
      case POSTED -> {
        requireBookable();
        transactionId = reference;
        state = JobOrderLinkedMoneyState.POSTED;
      }
      case AUTHORISED -> {
        requireBookable();
        authorisationId = reference;
        state = JobOrderLinkedMoneyState.AUTHORISED;
      }
      case REJECTED, CANCELLED -> {
        requireState(JobOrderLinkedMoneyState.WAITING);
        if (!reference.equals(requestId)) {
          throw conflict();
        }
        state =
            decision.kind() == Outcome.REJECTED
                ? JobOrderLinkedMoneyState.REJECTED
                : JobOrderLinkedMoneyState.CANCELLED;
      }
      case REVERSED -> {
        requireState(JobOrderLinkedMoneyState.POSTED);
        if (!Objects.equals(transactionId, decision.originalId())) {
          throw conflict();
        }
        reversalId = reference;
        state =
            actualPayment
                ? JobOrderLinkedMoneyState.CLARIFICATION
                : JobOrderLinkedMoneyState.REVERSED;
      }
    }
    decisionActorId = actor;
    updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * Cancels the waiting attempt for a currency wipe with the patch change as its reason
   * (REQ-PATCH-043); a reported actual payment keeps its binding, as after any cancellation.
   *
   * @param patchChangeId the patch change
   */
  void cancelForCurrencyWipe(UUID patchChangeId) {
    requireState(JobOrderLinkedMoneyState.WAITING);
    state = JobOrderLinkedMoneyState.CANCELLED;
    cancelledByPatchChange = patchChangeId;
    decisionActorId = null;
    updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  void requireState(JobOrderLinkedMoneyState expected) {
    if (!active || state != expected) {
      throw conflict();
    }
  }

  private void requireBookable() {
    if (state != JobOrderLinkedMoneyState.SUBMITTING && state != JobOrderLinkedMoneyState.WAITING) {
      throw conflict();
    }
  }

  static BusinessConflictException conflict() {
    return new BusinessConflictException("orders.money.bank.attemptChanged");
  }

  /** Validated order effect, party selection and monetary terms of a submitted attempt. */
  record Terms(
      JobOrderMoneyKind kind,
      @Nullable UUID holderId,
      @Nullable UUID destinationHolderId,
      @Nullable UUID recipientId,
      @Nullable UUID commitmentId,
      @Nullable JobOrderMoneySelection selection,
      BigDecimal amount,
      BigDecimal provision,
      RateSnapshot rate,
      BigDecimal actualFee,
      JobOrderMoneyFeeFunding feeFunding,
      BigDecimal sourceBinding,
      boolean actualPayment,
      @Nullable UUID accountHoldingId) {

    Terms(
        JobOrderMoneyKind kind,
        UUID holderId,
        @Nullable UUID destinationHolderId,
        @Nullable UUID recipientId,
        @Nullable UUID commitmentId,
        JobOrderMoneySelection selection,
        BigDecimal amount,
        BigDecimal provision,
        RateSnapshot rate,
        BigDecimal actualFee,
        JobOrderMoneyFeeFunding feeFunding,
        BigDecimal sourceBinding,
        boolean actualPayment) {
      this(
          kind,
          holderId,
          destinationHolderId,
          recipientId,
          commitmentId,
          selection,
          amount,
          provision,
          rate,
          actualFee,
          feeFunding,
          sourceBinding,
          actualPayment,
          null);
    }
  }
}
