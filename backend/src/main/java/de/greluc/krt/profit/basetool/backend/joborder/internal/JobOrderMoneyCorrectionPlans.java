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

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy;
import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Validates and records one order's part of a linked financial correction. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyCorrectionPlans {

  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyCorrectionEntryRepository history;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyQueries queries;
  private final JobOrderService lifecycle;
  private final TransferFeePolicy fees;
  private final JobOrderMoneyEffects effects;
  private final JobOrderMoneyCommitmentCorrection creditCorrection;

  boolean needsQueue(JobOrder order, UUID operationId, Map<UUID, Terms> terms) {
    UUID orderId = order.getId();
    if (order.getStatus() == JobOrderStatus.CANCELLED
        || operations.existsByOrderIdAndOperationId(orderId, operationId)
        || operations.isRefused(orderId, operationId)) {
      return false;
    }
    var revised =
        ledger.deliveredAfter(
            orderId,
            terms.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().amount())));
    return targets
        .amountFor(orderId)
        .filter(target -> changesLifecycle(order.getStatus(), target, revised))
        .isPresent();
  }

  Plan prepare(
      JobOrder order,
      CorrectJobOrderMoneyDto dto,
      Map<UUID, Terms> terms,
      UUID actor,
      @Nullable UUID peerId,
      boolean checkHolderVersions) {
    var current = ledger.read(order.getId());
    var originals = originalFacts(current, terms, peerId);
    var affected = affectedHolders(order, current, originals, actor);
    if (checkHolderVersions) {
      affected.forEach((id, holder) ->
          OptimisticLock.check(
              holder.getRevision(), dto.holderVersions().get(id), JobOrderMoneyHolder.class, id));
    }
    var entries =
        originals.entrySet().stream()
            .map(entry -> {
              var fact = entry.getValue();
              var change = Objects.requireNonNull(terms.get(entry.getKey()));
              var correction =
                  new JobOrderMoneyCorrectionEntry(
                      order.getId(),
                      dto.operationId(),
                      fact.getOperationId(),
                      current.effective(fact),
                      revisedAmounts(fact, change));
              var prior =
                  current.corrections().reversed().stream()
                      .filter(previous ->
                          previous.getOriginalOperationId().equals(fact.getOperationId()))
                      .findFirst()
                      .orElse(null);
              creditCorrection.attachCredit(
                  order, fact, correction, prior, change.removeAttribution());
              return correction;
            })
            .toList();
    if (entries.stream().noneMatch(entry -> entry.changesAmounts() || entry.changesCredit())) {
      throw new BadRequestException("orders.money.correction.noChange");
    }
    var revised = current.revised(entries);
    if (!revised.nonnegative()) {
      throw new UnavailableCorrection();
    }
    boolean touchesHandover =
        originals.values().stream().anyMatch(JobOrderMoneyCorrectionPlans::affectsHandover);
    boolean changesLifecycle = false;
    if (touchesHandover) {
      JobOrderMoneyTarget target = targets.findById(order.getId()).orElseThrow();
      OptimisticLock.check(
          target.getVersion(), dto.targetVersion(), JobOrderMoneyTarget.class, order.getId());
      changesLifecycle =
          changesLifecycle(order.getStatus(), target.getAmount(), revised.handedOver());
    }
    var noticeParties = new ArrayList<JobOrderMoneyEffects.CorrectionParty>();
    for (var holder : affected.values()) {
      UUID account = holder.getUserId();
      if (account != null) {
        noticeParties.add(new JobOrderMoneyEffects.CorrectionParty(holder.getId(), null, account));
      }
    }
    for (var fact : originals.values()) {
      UUID recipientId = fact.getRecipientId();
      UUID account = recipientId == null ? null : recipients.accountFor(order.getId(), recipientId);
      if (account != null) {
        noticeParties.add(new JobOrderMoneyEffects.CorrectionParty(null, recipientId, account));
      }
    }
    return new Plan(affected, entries, revised, changesLifecycle, touchesHandover, noticeParties);
  }

  private static boolean affectsHandover(JobOrderMoneyOperation fact) {
    return fact.getKind().delivers() || fact.getKind() == JobOrderMoneyKind.REQUESTER_RETURN;
  }

  private static boolean changesLifecycle(
      JobOrderStatus status, BigDecimal target, BigDecimal delivered) {
    boolean met = delivered.compareTo(target) >= 0;
    return (status.isActive() && met) || (status == JobOrderStatus.COMPLETED && !met);
  }

  private Map<UUID, JobOrderMoneyHolder> affectedHolders(
      JobOrder order,
      JobOrderMoneyLedger.Ledger current,
      Map<UUID, JobOrderMoneyOperation> originals,
      UUID actor) {
    var affected = new LinkedHashMap<UUID, JobOrderMoneyHolder>();
    boolean responsible = access.responsible(order);
    for (var fact : originals.values()) {
      var source = current.holder(fact.getHolderId());
      addAffected(affected, source, actor, responsible);
      if (fact.getDestinationHolderId() != null) {
        addAffected(affected, current.holder(fact.getDestinationHolderId()), actor, responsible);
      }
      if (!responsible
          && fact.getRecipientId() != null
          && !actor.equals(recipients.accountFor(order.getId(), fact.getRecipientId()))
          && !access.requester(order)) {
        throw new AccessDeniedException("The correction changes a foreign requester payment.");
      }
    }
    return affected;
  }

  private static void addAffected(
      Map<UUID, JobOrderMoneyHolder> affected,
      JobOrderMoneyHolder holder,
      UUID actor,
      boolean responsible) {
    if (!responsible && !actor.equals(holder.getUserId())) {
      throw new AccessDeniedException("The correction changes a foreign holding.");
    }
    affected.put(Objects.requireNonNull(holder.getId()), holder);
  }

  private static Map<UUID, JobOrderMoneyOperation> originalFacts(
      JobOrderMoneyLedger.Ledger current, Map<UUID, Terms> terms, @Nullable UUID peerId) {
    var matches =
        current.facts().stream()
            .filter(fact -> terms.containsKey(fact.getOperationId()))
            .collect(Collectors.toMap(JobOrderMoneyOperation::getOperationId, Function.identity()));
    if (matches.size() != terms.size()
        || matches.values().stream()
            .anyMatch(fact ->
                fact.isBankHandling()
                    || fact.getKind() == JobOrderMoneyKind.CORRECTION
                    || (fact.getPeerOrderId() != null && !fact.getPeerOrderId().equals(peerId)))) {
      throw new BadRequestException("orders.money.correction.originalRequired");
    }
    return matches;
  }

  private JobOrderMoneyAmounts revisedAmounts(JobOrderMoneyOperation fact, Terms terms) {
    BigDecimal amount = terms.amount();
    BigDecimal provision = terms.provision();
    if (fact.getKind() == JobOrderMoneyKind.PROVISION) {
      if (provision != null) {
        throw new BadRequestException("orders.money.correction.provision");
      }
      return new JobOrderMoneyAmounts(amount, amount, BigDecimal.ZERO);
    }
    BigDecimal rate = fact.getFeeRate();
    BigDecimal newFunds = Objects.requireNonNullElse(provision, BigDecimal.ZERO);
    JobOrderMoneyKind kind = fact.getKind();
    if ((rate == null || kind == JobOrderMoneyKind.PURPOSE_HANDOVER) && newFunds.signum() != 0) {
      throw new BadRequestException("orders.money.correction.provision");
    }
    BigDecimal fee = rate == null ? BigDecimal.ZERO : fees.feeOn(amount, new RateSnapshot(rate));
    if ((kind == JobOrderMoneyKind.REQUESTER_RETURN || kind == JobOrderMoneyKind.SETTLEMENT)
        && newFunds.compareTo(fee) > 0) {
      throw new BadRequestException("orders.money.settlement.feeProvision.excess");
    }
    if (kind == JobOrderMoneyKind.REQUESTER_RETURN
        && fact.getHolderId().equals(fact.getDestinationHolderId())
        && newFunds.compareTo(fee) != 0) {
      throw new BadRequestException("orders.money.correction.provision");
    }
    return new JobOrderMoneyAmounts(amount, newFunds, fee);
  }

  JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> post(
      JobOrder order, UUID operationId, UUID actor, Plan plan, @Nullable UUID peerId) {
    return post(order, operationId, actor, plan, peerId, Set.of());
  }

  JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> post(
      JobOrder order,
      UUID operationId,
      UUID actor,
      Plan plan,
      @Nullable UUID peerId,
      Set<UUID> noticedAccounts) {
    var holder = plan.holders().values().iterator().next();
    final var fact =
        operations.saveAndFlush(
            JobOrderMoneyOperation.correction(
                order.getId(), operationId, Objects.requireNonNull(holder.getId()), actor, peerId));
    history.saveAll(plan.entries());
    creditCorrection.apply(order, operationId, plan.entries());
    plan.holders().values().forEach(JobOrderMoneyHolder::recordChange);
    if (plan.lifecycleChanged()) {
      if (order.getStatus() == JobOrderStatus.COMPLETED) {
        lifecycle.reopenUnderfulfilledMoneyOrder(order);
      } else {
        lifecycle.completeJobOrderWithinTransaction(order);
      }
    }
    var parties =
        plan.parties().stream()
            .filter(party -> !noticedAccounts.contains(party.accountId()))
            .toList();
    effects.correctionRecorded(
        order,
        parties,
        fact,
        actor,
        plan.lifecycleChanged(),
        plan.affectsRecipients(),
        plan.entries().stream().anyMatch(JobOrderMoneyCorrectionEntry::changesCredit));
    var audit =
        JobOrderEdit.audited(
            order,
            AuditEventType.JOB_ORDER_MONEY_CORRECTED,
            AuditDetails.of("operationId", operationId)
                .with(
                    "correctedOperations",
                    plan.entries().stream()
                        .map(JobOrderMoneyCorrectionEntry::getOriginalOperationId)
                        .toList()));
    return new JobOrderEditor.MoneyEdit<>(Optional.of(queries.projectOutcome(fact, holder)), audit);
  }

  JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> replay(
      JobOrder order,
      JobOrderMoneyOperation fact,
      Map<UUID, Terms> terms,
      UUID actor,
      @Nullable UUID peerId) {
    if (!actor.equals(fact.getActorId())) {
      throw new AccessDeniedException("Caller may not replay this operation.");
    }
    if (fact.getKind() != JobOrderMoneyKind.CORRECTION
        || !Objects.equals(fact.getPeerOrderId(), peerId)) {
      throw new BusinessConflictException("orders.money.operation.termsMismatch");
    }
    var current = ledger.read(order.getId());
    Map<UUID, JobOrderMoneyOperation> originals;
    try {
      originals = originalFacts(current, terms, peerId);
    } catch (BadRequestException invalid) {
      throw new BusinessConflictException("orders.money.operation.termsMismatch");
    }
    var stored =
        history.findAllByOrderIdAndCorrectionOperationIdOrderByIdAsc(
            order.getId(), fact.getOperationId());
    if (stored.size() != terms.size()
        || stored.stream()
            .anyMatch(entry ->
                !matchesStored(
                    entry,
                    originals.get(entry.getOriginalOperationId()),
                    terms.get(entry.getOriginalOperationId())))) {
      throw new BusinessConflictException("orders.money.operation.termsMismatch");
    }
    affectedHolders(order, current, originals, actor);
    creditCorrection.authorizeReplay(order, stored);
    return result(
        order, Optional.of(queries.projectOutcome(fact, current.holder(fact.getHolderId()))));
  }

  private boolean matchesStored(
      JobOrderMoneyCorrectionEntry entry,
      @Nullable JobOrderMoneyOperation original,
      @Nullable Terms change) {
    if (original == null
        || change == null
        || change.removeAttribution() != entry.isRemoveAttributionRequested()) {
      return false;
    }
    try {
      return sameAmounts(entry.revised(), revisedAmounts(original, change));
    } catch (BadRequestException invalid) {
      return false;
    }
  }

  private static boolean sameAmounts(JobOrderMoneyAmounts first, JobOrderMoneyAmounts second) {
    return first.amount().compareTo(second.amount()) == 0
        && first.provision().compareTo(second.provision()) == 0
        && first.fee().compareTo(second.fee()) == 0;
  }

  static JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> result(
      JobOrder order, Optional<JobOrderMoneyOutcome> outcome) {
    return new JobOrderEditor.MoneyEdit<>(outcome, JobOrderEdit.unchanged(order));
  }

  static Map<UUID, Terms> terms(CorrectJobOrderMoneyDto dto) {
    var terms = new HashMap<UUID, Terms>();
    for (var entry : dto.entries()) {
      String provision = entry.provision();
      var added =
          new Terms(
              WholeAuec.nonnegative(entry.amount()),
              provision == null ? null : WholeAuec.nonnegative(provision),
              entry.removeAttribution());
      if (terms.putIfAbsent(entry.originalOperationId(), added) != null) {
        throw new BadRequestException("orders.money.correction.duplicate");
      }
    }
    return terms;
  }

  record Terms(BigDecimal amount, @Nullable BigDecimal provision, boolean removeAttribution) {}

  record Plan(
      Map<UUID, JobOrderMoneyHolder> holders,
      List<JobOrderMoneyCorrectionEntry> entries,
      JobOrderMoneyLedger.Ledger revised,
      boolean lifecycleChanged,
      boolean affectsRecipients,
      List<JobOrderMoneyEffects.CorrectionParty> parties) {

    JobOrderMoneyCorrectionPreview preview(
        Map<UUID, Long> linkedHolderVersions, @Nullable BigDecimal linkedHoldings) {
      return new JobOrderMoneyCorrectionPreview(
          entries.stream().map(JobOrderMoneyCorrectionEntry::view).toList(),
          revised.holdings().toPlainString(),
          revised.provided().toPlainString(),
          revised.handedOver().toPlainString(),
          revised.fees().toPlainString(),
          linkedHolderVersions,
          linkedHoldings == null ? null : linkedHoldings.toPlainString());
    }

    Set<UUID> linkedAccounts() {
      return parties.stream()
          .map(JobOrderMoneyEffects.CorrectionParty::accountId)
          .collect(Collectors.toUnmodifiableSet());
    }

    Map<UUID, Long> holderVersions() {
      return holders.entrySet().stream()
          .collect(
              Collectors.toUnmodifiableMap(
                  Map.Entry::getKey, item -> item.getValue().getRevision()));
    }
  }

  static final class UnavailableCorrection extends RuntimeException {
    UnavailableCorrection() {
      super("Correction would leave a negative balance");
    }
  }
}
