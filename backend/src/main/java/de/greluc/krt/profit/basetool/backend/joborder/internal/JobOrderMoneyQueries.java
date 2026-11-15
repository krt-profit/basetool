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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderPatchLabels;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reconstructs current order money and authorizes durable outcome reads. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class JobOrderMoneyQueries {

  private final JobOrderRepository orders;
  private final JobOrderAccessPolicy access;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderLinkedMoneyAttemptRepository attempts;
  private final JobOrderMoneyHolders holderChoices;
  private final AuthHelperService auth;
  private final JobOrderMoneyAccess moneyAccess;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyTargetChangeRepository targetChanges;
  private final JobOrderMoneyCorrectionEntryRepository corrections;
  private final JobOrderMoneyHolderHistory holderHistory;
  private final JobOrderMoneyPairedCorrectionChoices pairedCorrectionChoices;
  private final ObjectProvider<JobOrderBankPort> bankPort;
  private final JobOrderPatchLabels patchChangeLabels;

  /**
   * Reconstructs current reported amounts from effective monetary facts.
   *
   * @param orderId the aUEC order
   * @return the current money view
   */
  public JobOrderMoneyView getMoney(UUID orderId) {
    JobOrder order = requireReadableMoneyOrder(orderId);
    JobOrderMoneyTarget target = targets.findById(orderId).orElseThrow();
    var booked = ledger.read(orderId);
    var linked = attempts.findAllByOrderIdOrderBySubmittedAtAscIdAsc(orderId);
    List<JobOrderMoneyHolder> identities =
        booked.identities().values().stream()
            .sorted(Comparator.comparing(JobOrderMoneyHolder::getId))
            .toList();
    Map<UUID, String> names = peopleNames(identities, booked.facts(), linked);
    Map<UUID, String> causes = patchLabels(booked.facts(), linked);
    List<JobOrderMoneyRecipientView> designations = recipients.views(orderId);
    Map<UUID, JobOrderMoneyRecipientView> recipientsById =
        designations.stream().collect(Collectors.toMap(JobOrderMoneyRecipientView::id, r -> r));
    List<JobOrderMoneyHolderView> rows =
        identities.stream()
            .filter(holder -> holder.getMergedIntoId() == null)
            .map(holder ->
                new JobOrderMoneyHolderView(
                    Objects.requireNonNull(holder.getId()),
                    holder.getUserId(),
                    label(holder, names),
                    booked.balance(holder.getId()).toPlainString(),
                    booked.availableBalance(holder.getId()).toPlainString(),
                    holder.getRevision()))
            .toList();
    Map<UUID, List<JobOrderMoneyCorrectionView>> correctionViews =
        booked.corrections().stream()
            .collect(
                Collectors.groupingBy(
                    JobOrderMoneyCorrectionEntry::getCorrectionOperationId,
                    Collectors.mapping(JobOrderMoneyCorrectionEntry::view, Collectors.toList())));
    var visiblePeers = visiblePeers(booked.facts());
    boolean validResponsibility = !responsibilityInvalid(order);
    UUID actor =
        auth.currentUserId().filter(id -> holderChoices.activeUser(id).isPresent()).orElse(null);
    boolean responsible = validResponsibility && actor != null && moneyAccess.responsible(order);
    boolean requester = validResponsibility && actor != null && moneyAccess.requester(order);
    Set<UUID> correctablePaired =
        validResponsibility && actor != null
            ? pairedCorrectionChoices.eligibleOriginals(order, actor, visiblePeers)
            : Set.of();
    List<JobOrderMoneyOutcome> history =
        booked.facts().stream()
            .map(fact ->
                JobOrderMoneyOutcome.of(
                    fact,
                    sourceName(fact, booked, names, recipientsById),
                    fact.getActorId() == null ? null : names.get(fact.getActorId()),
                    destinationName(fact, booked, names, recipientsById),
                    visiblePeer(fact, visiblePeers),
                    correctionViews.getOrDefault(fact.getOperationId(), List.of()),
                    validResponsibility
                        ? correctionBasis(
                            fact,
                            booked,
                            recipientsById,
                            actor,
                            responsible,
                            requester,
                            correctablePaired.contains(fact.getOperationId()))
                        : null,
                    patchLabel(causes, fact.getPatchChangeId())))
            .toList();
    boolean self = validResponsibility && actor != null && order.getStatus().isActive();
    boolean unitWriter = responsible || requester;
    boolean editsOrder = unitWriter && order.getStatus() != JobOrderStatus.CANCELLED;
    boolean ownsSettlement =
        actor != null
            && (rows.stream().anyMatch(row -> actor.equals(row.userId()))
                || designations.stream()
                    .anyMatch(designation -> actor.equals(designation.userId())));
    BigDecimal outstanding = target.getAmount().subtract(booked.handedOver());
    return new JobOrderMoneyView(
        target.getAmount().toPlainString(),
        Objects.requireNonNull(target.getVersion()),
        booked.provided().toPlainString(),
        booked.holdings().toPlainString(),
        booked.handedOver().toPlainString(),
        outstanding.max(BigDecimal.ZERO).toPlainString(),
        rows,
        history,
        self,
        self && moneyAccess.responsible(order),
        validResponsibility && auth.isAdmin() && booked.facts().isEmpty() && linked.isEmpty(),
        booked.fees().toPlainString(),
        outstanding.negate().max(BigDecimal.ZERO).toPlainString(),
        designations,
        self && unitWriter,
        editsOrder,
        editsOrder,
        order.getMoneyInformationVersion(),
        self && moneyAccess.responsible(order),
        self && moneyAccess.requester(order),
        targetHistory(orderId),
        booked.purposeInflow().toPlainString(),
        booked.purposeOutflow().toPlainString(),
        booked.released().toPlainString(),
        booked.shortages().toPlainString(),
        booked.settled().toPlainString(),
        !order.getStatus().isActive()
            && (booked.holdings().signum() > 0 || outstanding.signum() < 0),
        validResponsibility,
        responsible,
        requester,
        holderHistory.views(orderId),
        validResponsibility
            && order.getStatus() != JobOrderStatus.REJECTED
            && (unitWriter || ownsSettlement),
        unitWriter,
        designations.stream()
            .filter(designation ->
                !booked.recipientAliases().containsKey(designation.id())
                    && booked.availableForPersonalReturn(designation.id(), null).signum() > 0)
            .map(JobOrderMoneyRecipientView::id)
            .toList(),
        linked.stream()
            .map(attempt -> projectLinkedOutcome(attempt, booked, names, recipientsById, causes))
            .toList(),
        accountHoldingViews(booked),
        unitWriter ? accountReturns(booked) : List.of(),
        actor == null
            ? List.of()
            : linked.stream()
                .filter(attempt ->
                    JobOrderMoneyAccess.repairable(order, attempt)
                        && moneyAccess.mayRepair(order, attempt, actor, booked))
                .map(JobOrderLinkedMoneyAttempt::getOperationId)
                .toList());
  }

  private static List<JobOrderMoneyAccountReturnView> accountReturns(
      JobOrderMoneyLedger.Ledger booked) {
    return booked.accountRequesterBalances().keySet().stream()
        .sorted(
            Comparator.comparing(JobOrderMoneyAvailability.AccountReceipt::holdingId)
                .thenComparing(JobOrderMoneyAvailability.AccountReceipt::recipientId))
        .<JobOrderMoneyAccountReturnView>mapMulti((receipt, choices) -> {
          var amount =
              booked.availableForAccountReturn(receipt.holdingId(), receipt.recipientId(), null);
          if (amount.signum() > 0) {
            choices.accept(
                new JobOrderMoneyAccountReturnView(
                    receipt.holdingId(), receipt.recipientId(), amount.toPlainString()));
          }
        })
        .toList();
  }

  private List<JobOrderMoneyAccountHoldingView> accountHoldingViews(
      JobOrderMoneyLedger.Ledger booked) {
    if (booked.accountHoldings().isEmpty()) {
      return List.of();
    }
    var port = bankPort.getIfUnique();
    return booked.accountHoldings().values().stream()
        .sorted(Comparator.comparing(JobOrderMoneyAccountHolding::getId))
        .map(holding -> {
          UUID id = Objects.requireNonNull(holding.getId());
          UUID bankAccount = holding.getBankAccountId();
          return new JobOrderMoneyAccountHoldingView(
              id,
              port != null && port.canSeeAccount(bankAccount) ? bankAccount : null,
              booked.accountBalance(id).toPlainString(),
              booked.availableAccountBalance(id).toPlainString(),
              holding.getRevision());
        })
        .toList();
  }

  private Set<UUID> visiblePeers(List<JobOrderMoneyOperation> facts) {
    var peers =
        facts.stream()
            .map(JobOrderMoneyOperation::getPeerOrderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return orders.findAllById(peers).stream()
        .filter(access::canSeeJobOrder)
        .map(JobOrder::getId)
        .collect(Collectors.toSet());
  }

  private static @Nullable UUID visiblePeer(JobOrderMoneyOperation operation, Set<UUID> visible) {
    UUID peer = operation.getPeerOrderId();
    return peer != null && visible.contains(peer) ? peer : null;
  }

  private List<JobOrderMoneyTargetChangeView> targetHistory(UUID orderId) {
    List<JobOrderMoneyTargetChange> changes =
        targetChanges.findAllByOrderIdOrderByRecordedAtAscIdAsc(orderId);
    Map<UUID, String> names =
        holderChoices.namesOf(
            changes.stream()
                .map(JobOrderMoneyTargetChange::getActorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    return changes.stream()
        .map(change ->
            new JobOrderMoneyTargetChangeView(
                Objects.requireNonNull(change.getId()),
                change.getPreviousTarget().toPlainString(),
                change.getTarget().toPlainString(),
                change.getRecordedAt(),
                change.getActorId() == null ? null : names.get(change.getActorId())))
        .toList();
  }

  private static @Nullable JobOrderMoneyCorrectionBasisView correctionBasis(
      JobOrderMoneyOperation operation,
      JobOrderMoneyLedger.Ledger booked,
      Map<UUID, JobOrderMoneyRecipientView> designations,
      @Nullable UUID actor,
      boolean responsible,
      boolean requester,
      boolean pairedAuthorized) {
    if (actor == null
        || !booked.canCorrect(operation)
        || operation.getKind() == JobOrderMoneyKind.CORRECTION
        || (operation.getPeerOrderId() != null && !pairedAuthorized)) {
      return null;
    }
    UUID accountHoldingId = operation.getAccountHoldingId();
    if (accountHoldingId != null) {
      return responsible
          ? correctionBasis(operation, booked, List.of(accountHoldingId), accountHoldingId)
          : null;
    }
    var source = booked.holder(Objects.requireNonNull(operation.getHolderId()));
    UUID destinationId = operation.getDestinationHolderId();
    var destination = destinationId == null ? source : booked.holder(destinationId);
    UUID recipientId = operation.getRecipientId();
    UUID recipientAccount =
        recipientId == null ? null : Objects.requireNonNull(designations.get(recipientId)).userId();
    if (!responsible
        && (!actor.equals(source.getUserId())
            || !actor.equals(destination.getUserId())
            || (recipientId != null && !requester && !actor.equals(recipientAccount)))) {
      return null;
    }
    UUID sourceId = Objects.requireNonNull(source.getId());
    UUID targetId = Objects.requireNonNull(destination.getId());
    return correctionBasis(
        operation,
        booked,
        sourceId.equals(targetId) ? List.of(sourceId) : List.of(sourceId, targetId),
        null);
  }

  private static JobOrderMoneyCorrectionBasisView correctionBasis(
      JobOrderMoneyOperation operation,
      JobOrderMoneyLedger.Ledger booked,
      List<UUID> positionIds,
      @Nullable UUID accountHoldingId) {
    var amounts = booked.effective(operation);
    return new JobOrderMoneyCorrectionBasisView(
        amounts.amount().toPlainString(),
        amounts.provision().toPlainString(),
        positionIds,
        operation.getKind().delivers() || operation.getKind() == JobOrderMoneyKind.REQUESTER_RETURN,
        operation.getCommitmentId() != null,
        accountHoldingId);
  }

  private static @Nullable String sourceName(
      JobOrderMoneyOperation operation,
      JobOrderMoneyLedger.Ledger booked,
      Map<UUID, String> names,
      Map<UUID, JobOrderMoneyRecipientView> designations) {
    UUID recipientId = operation.getRecipientId();
    return operation.getKind() == JobOrderMoneyKind.REQUESTER_RETURN && recipientId != null
        ? Objects.requireNonNull(designations.get(recipientId)).name()
        : holderLabel(operation.getHolderId(), booked, names);
  }

  private static @Nullable String destinationName(
      JobOrderMoneyOperation operation,
      JobOrderMoneyLedger.Ledger booked,
      Map<UUID, String> names,
      Map<UUID, JobOrderMoneyRecipientView> designations) {
    if (operation.getKind() == JobOrderMoneyKind.SETTLEMENT) {
      UUID beneficiary = operation.getSelectedUserId();
      return beneficiary == null ? operation.getSelectedAccountlessName() : names.get(beneficiary);
    }
    UUID destinationId = operation.getDestinationHolderId();
    if (destinationId != null) {
      return label(booked.holder(destinationId), names);
    }
    UUID recipientId = operation.getRecipientId();
    return recipientId == null
        ? null
        : Objects.requireNonNull(designations.get(recipientId)).name();
  }

  private Map<UUID, String> peopleNames(
      Collection<JobOrderMoneyHolder> identities,
      List<JobOrderMoneyOperation> facts,
      List<JobOrderLinkedMoneyAttempt> linked) {
    var ids = new HashSet<UUID>();
    identities.forEach(holder -> {
      if (holder.getUserId() != null) {
        ids.add(holder.getUserId());
      }
    });
    facts.forEach(operation -> {
      if (operation.getActorId() != null) {
        ids.add(operation.getActorId());
      }
      UUID beneficiary = operation.getSelectedUserId();
      if (operation.getKind() == JobOrderMoneyKind.SETTLEMENT && beneficiary != null) {
        ids.add(beneficiary);
      }
    });
    linked.forEach(attempt -> {
      if (attempt.getActorId() != null) {
        ids.add(attempt.getActorId());
      }
      if (attempt.getCounterpartyUserId() != null) {
        ids.add(attempt.getCounterpartyUserId());
      }
    });
    return holderChoices.namesOf(ids);
  }

  /**
   * Reads the current labels of the patch changes behind the given shortages and attempts.
   *
   * @param facts the money facts, of which a currency-wipe shortage names its patch change
   * @param attempts the linked attempts, of which a cancelled one may name its patch change
   * @return the label by patch change id
   */
  private Map<UUID, String> patchLabels(
      Collection<JobOrderMoneyOperation> facts, Collection<JobOrderLinkedMoneyAttempt> attempts) {
    Set<UUID> ids =
        Stream.concat(
                facts.stream().map(JobOrderMoneyOperation::getPatchChangeId),
                attempts.stream().map(JobOrderLinkedMoneyAttempt::getCancelledByPatchChange))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return ids.isEmpty() ? Map.of() : patchChangeLabels.labels(ids);
  }

  private static @Nullable String patchLabel(
      Map<UUID, String> labels, @Nullable UUID patchChangeId) {
    return patchChangeId == null ? null : labels.get(patchChangeId);
  }

  private static @Nullable String label(JobOrderMoneyHolder holder, Map<UUID, String> names) {
    return holder.getUserId() == null ? holder.getAccountlessName() : names.get(holder.getUserId());
  }

  private static @Nullable String holderLabel(
      @Nullable UUID holderId, JobOrderMoneyLedger.Ledger booked, Map<UUID, String> names) {
    return holderId == null ? null : label(booked.holder(holderId), names);
  }

  /**
   * Recovers a decided money operation without performing a write.
   *
   * @param orderId the aUEC order whose current read access governs lookup
   * @param operationId the caller's logical operation identity
   * @return the accepted outcome, or empty for a durable refusal with no monetary effect
   * @throws NotFoundException if no outcome is known
   */
  public Optional<JobOrderMoneyOutcome> getOutcome(UUID orderId, UUID operationId) {
    requireReadableMoneyOrder(orderId);
    var linked = attempts.findByOrderIdAndOperationIdAndActiveTrue(orderId, operationId);
    if (linked.isPresent()) {
      return linked.map(this::projectLinkedOutcome);
    }
    var accepted =
        operations.findByOrderIdAndOperationId(orderId, operationId).map(this::projectOutcome);
    if (accepted.isEmpty() && !operations.isRefused(orderId, operationId)) {
      throw new NotFoundException("orders.money.operation.unknown");
    }
    return accepted;
  }

  /**
   * Searches active accounts only for a current responsible writer who may record a provision.
   *
   * @param orderId the order owning the picker
   * @param query the optional name fragment
   * @return the bounded list of eligible account references
   */
  public List<JobOrderMoneyHolderCandidate> holderCandidates(UUID orderId, @Nullable String query) {
    JobOrder order = requireReadableMoneyOrder(orderId);
    if (access.responsibilityInvalid(order)
        || !order.getStatus().isActive()
        || !access.canTransferResponsibility(order)
        || auth.currentUserId().flatMap(holderChoices::activeUser).isEmpty()) {
      throw new AccessDeniedException("Caller may not choose a holder for this order.");
    }
    return holderChoices.candidates(query);
  }

  JobOrderMoneyOutcome projectLinkedOutcome(JobOrderLinkedMoneyAttempt attempt) {
    var booked = ledger.read(attempt.getOrderId());
    var names = peopleNames(booked.identities().values(), List.of(), List.of(attempt));
    var designations =
        recipients.views(attempt.getOrderId()).stream()
            .collect(Collectors.toMap(JobOrderMoneyRecipientView::id, Function.identity()));
    return projectLinkedOutcome(
        attempt, booked, names, designations, patchLabels(List.of(), List.of(attempt)));
  }

  private static JobOrderMoneyOutcome projectLinkedOutcome(
      JobOrderLinkedMoneyAttempt attempt,
      JobOrderMoneyLedger.Ledger booked,
      Map<UUID, String> names,
      Map<UUID, JobOrderMoneyRecipientView> designations,
      Map<UUID, String> causes) {
    String holderName = holderLabel(attempt.getHolderId(), booked, names);
    String counterpartyName =
        attempt.getCounterpartyUserId() == null
            ? attempt.getCounterpartyName()
            : names.get(attempt.getCounterpartyUserId());
    String recipientName =
        attempt.getRecipientId() != null
            ? Objects.requireNonNull(designations.get(attempt.getRecipientId())).name()
            : attempt.getDestinationHolderId() != null
                ? label(booked.holder(attempt.getDestinationHolderId()), names)
                : attempt.getKind() == JobOrderMoneyKind.SETTLEMENT ? counterpartyName : null;
    boolean clarified =
        attempt.getState() == JobOrderLinkedMoneyState.CLARIFICATION
            && booked.corrections().stream()
                .anyMatch(entry -> attempt.getOperationId().equals(entry.getOriginalOperationId()));
    return new JobOrderMoneyOutcome(
        attempt.getOperationId(),
        attempt.getHolderId(),
        attempt.getAmount().toPlainString(),
        true,
        attempt.getSubmittedAt(),
        holderName,
        attempt.getActorId() == null ? null : names.get(attempt.getActorId()),
        attempt.getKind(),
        attempt.getDestinationHolderId(),
        attempt.getRecipientId(),
        recipientName,
        attempt.orderFee().toPlainString(),
        attempt.getRate().toPlainString(),
        attempt.getProvision().toPlainString(),
        null,
        List.of(),
        null,
        attempt.getState(),
        attempt.getRevision(),
        attempt.isActualPayment(),
        attempt.getActualFee().toPlainString(),
        attempt.getFeeFunding(),
        counterpartyName,
        attempt.isActive(),
        clarified,
        attempt.getAccountHoldingId(),
        patchLabel(causes, attempt.getCancelledByPatchChange()));
  }

  JobOrderMoneyOutcome projectOutcome(JobOrderMoneyOperation operation) {
    UUID holderId = operation.getHolderId();
    return projectOutcome(
        operation, holderId == null ? null : holders.findById(holderId).orElseThrow());
  }

  JobOrderMoneyOutcome projectOutcome(
      JobOrderMoneyOperation operation, @Nullable JobOrderMoneyHolder holder) {
    return JobOrderMoneyOutcome.of(
        operation,
        operation.getKind() == JobOrderMoneyKind.REQUESTER_RETURN
            ? recipients
                .party(
                    recipients.require(
                        operation.getOrderId(), Objects.requireNonNull(operation.getRecipientId())))
                .name()
            : currentHolderName(holder),
        holderChoices.nameOf(operation.getActorId()),
        movementDestinationName(operation),
        visiblePeer(operation, visiblePeers(List.of(operation))),
        operation.getKind() == JobOrderMoneyKind.CORRECTION
            ? corrections
                .findAllByOrderIdAndCorrectionOperationIdOrderByIdAsc(
                    operation.getOrderId(), operation.getOperationId())
                .stream()
                .map(JobOrderMoneyCorrectionEntry::view)
                .toList()
            : List.of(),
        null,
        patchLabel(patchLabels(List.of(operation), List.of()), operation.getPatchChangeId()));
  }

  private @Nullable String currentHolderName(@Nullable JobOrderMoneyHolder holder) {
    if (holder == null) {
      return null;
    }
    UUID aliasTarget = holder.getMergedIntoId();
    return holderChoices.nameOf(
        aliasTarget == null ? holder : holders.findById(aliasTarget).orElseThrow());
  }

  private @Nullable String movementDestinationName(JobOrderMoneyOperation operation) {
    if (operation.getKind() == JobOrderMoneyKind.SETTLEMENT) {
      UUID beneficiary = operation.getSelectedUserId();
      return beneficiary == null
          ? operation.getSelectedAccountlessName()
          : holderChoices.nameOf(beneficiary);
    }
    UUID destinationId = operation.getDestinationHolderId();
    if (destinationId != null) {
      JobOrderMoneyHolder destination = holders.findById(destinationId).orElseThrow();
      UUID canonicalId = destination.getMergedIntoId();
      return holderChoices.nameOf(
          canonicalId == null ? destination : holders.findById(canonicalId).orElseThrow());
    }
    UUID recipientId = operation.getRecipientId();
    return recipientId == null
        ? null
        : recipients.party(recipients.require(operation.getOrderId(), recipientId)).name();
  }

  /**
   * Searches eligible accounts for a caller authorized to designate a requester recipient.
   *
   * @param orderId the owning order
   * @param query optional name fragment
   * @return eligible recipient accounts
   */
  public List<JobOrderMoneyHolderCandidate> recipientCandidates(
      UUID orderId, @Nullable String query) {
    JobOrder order = requireReadableMoneyOrder(orderId);
    moneyAccess.requireActiveActor();
    moneyAccess.requireUnitWriter(order);
    if (responsibilityInvalid(order) || !order.getStatus().isActive()) {
      throw new AccessDeniedException("Caller may not designate a recipient for this order.");
    }
    return holderChoices.candidates(query);
  }

  boolean responsibilityInvalid(JobOrder order) {
    return access.responsibilityInvalid(order);
  }

  JobOrder requireReadableMoneyOrder(UUID orderId) {
    JobOrder order =
        Entities.require(orders.findById(orderId), () -> "JobOrder not found: " + orderId);
    if (!access.canSeeJobOrder(order)) {
      throw new AccessDeniedException("Caller may not read this order.");
    }
    requireMoneyKind(order);
    return order;
  }

  static void requireMoneyKind(JobOrder order) {
    if (order.getType() != JobOrderType.AUEC) {
      throw new NotFoundException("orders.money.kind.required");
    }
  }
}
