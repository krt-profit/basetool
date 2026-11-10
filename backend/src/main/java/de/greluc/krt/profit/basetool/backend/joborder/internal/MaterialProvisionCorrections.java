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

import static de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts.fromQuanta;

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityCorrections;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragments;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialProvisionFact;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Corrects false original contribution facts without treating genuine later loss as an inverse. */
@Service
@RequiredArgsConstructor
public class MaterialProvisionCorrections {
  private final MaterialProvisionCreditRepository provisions;
  private final MaterialProvisionCredits credits;
  private final MaterialProvisionConnections connections;
  private final StockQuantityCorrections stockCorrections;
  private final MaterialClaimRepository contributions;
  private final JobOrderRepository orders;
  private final MaterialClaimAuthority authority;
  private final JobOrderResponsibilityGuard responsibility;
  private final MaterialPromiseCoordinator coordinator;
  private final MaterialPromiseCapacity capacity;
  private final MaterialContributionHistory history;
  private final JobOrderChangeEffects effects;
  private final EntityManager entityManager;

  /**
   * Applies one explicit false-fact correction with observed independent contribution versions.
   *
   * @param orderId the concrete order
   * @param provisionId the provision being corrected
   * @param correction observed versions and truthful corrected fact
   * @return refreshed retained contribution facts
   */
  @Transactional
  public List<MaterialContributionHistoryDto> correct(
      UUID orderId, UUID provisionId, MaterialProvisionCorrectionDto correction) {
    MaterialProvisionCredit credit =
        Entities.require(
            provisions.findById(provisionId), () -> "Material provision not found: " + provisionId);
    if (!credit.getOrderId().equals(orderId)) {
      throw new BadRequestException("orders.claim.correction.not_matching");
    }
    JobOrder observedOrder =
        Entities.require(orders.findById(orderId), () -> "JobOrder not found: " + orderId);
    responsibility.assertWritable(observedOrder);
    MaterialClaim observedOriginal = historicalClaim(credit.getClaimId());
    authority.assertCanCorrect(observedOrder, observedOriginal.getClaimingOrgUnit().getId());
    if (!Double.isFinite(correction.amount()) || correction.amount() < 0.0) {
      throw new BadRequestException("orders.claim.correction.amount_invalid");
    }
    long desired = MaterialQuantityArithmetic.quanta(correction.amount());
    long quantum =
        observedOriginal.getMaterial().getQuantityType() == QuantityType.PIECE ? 1000L : 1L;
    if (desired % quantum != 0L) {
      throw new BadRequestException("orders.claim.correction.amount_invalid");
    }
    var scope = new OrderMaterialScope(orderId, credit.getMaterialId());
    if (correction.kind() == MaterialProvisionCorrectionDto.Kind.QUANTITY
        && credit.getEvidence()
            != de.greluc.krt.profit.basetool.backend.joborder.api.MaterialProvisionEvidence
                .OBSERVED) {
      throw new de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException(
          "orders.claim.correction.legacy_quantity_unknown");
    }
    List<StockQuantityFragment> added = List.of();
    MaterialProvisionConnections.Plan connected = null;
    if (correction.kind() == MaterialProvisionCorrectionDto.Kind.QUANTITY) {
      long current = MaterialQuantityArithmetic.quanta(credit.getQuantityAmount());
      if (correction.targetClaimId() != null) {
        throw new BadRequestException("orders.claim.correction.amount_invalid");
      }
      List<StockQuantityFragment> evidence = quantityOf(credit);
      if (desired > current) {
        if (correction.targetStockRowId() == null) {
          throw new BadRequestException("orders.claim.correction.stock_row_required");
        }
        added =
            stockCorrections.addKnownQuantity(
                evidence,
                Math.subtractExact(desired, current),
                correction.targetStockRowId(),
                correction.stockVersions(),
                scope,
                credit.getId());
      } else {
        List<StockQuantityFragment> removed =
            StockQuantityFragments.subtract(
                evidence, StockQuantityFragments.prefix(evidence, desired));
        connected = connections.prepare(credit, removed);
        stockCorrections.removeKnownQuantity(
            removed, correction.stockVersions(), connected.scopes(), credit.getId());
      }
    } else if (correction.kind() == MaterialProvisionCorrectionDto.Kind.QUALITY
        && !correction.qualityStockRowIds().isEmpty()) {
      stockCorrections.correctKnownQuality(
          quantityOf(credit),
          correction.qualityStockRowIds(),
          requiredQuality(correction),
          correction.stockVersions(),
          scope,
          credit.getId());
    } else {
      coordinator.lock(List.of(scope));
    }
    if (connected != null) {
      connections.revalidate(connected);
    }
    entityManager.refresh(credit);
    JobOrder order =
        Entities.require(orders.findById(orderId), () -> "JobOrder not found: " + orderId);
    entityManager.refresh(order);
    responsibility.assertWritable(order);
    MaterialClaim original = historicalClaim(credit.getClaimId());
    authority.assertCanCorrect(order, original.getClaimingOrgUnit().getId());
    OptimisticLock.check(
        credit.getVersion(), correction.version(), MaterialProvisionCredit.class, provisionId);
    Long currentClaimVersion =
        contributions
            .currentHistoricalVersion(original.getId())
            .orElseThrow(() ->
                new de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException(
                    "orders.claim.correction.connected_facts_changed"));
    OptimisticLock.check(
        currentClaimVersion, original.getVersion(), MaterialClaim.class, original.getId());
    OptimisticLock.check(
        currentClaimVersion, correction.claimVersion(), MaterialClaim.class, original.getId());
    if (correction.kind() == MaterialProvisionCorrectionDto.Kind.ATTRIBUTION) {
      if (desired == 0L && correction.targetClaimId() == null && credit.getAmount() == 0.0) {
        return history.read(orderId);
      }
      MaterialClaim target = attributionTarget(order, credit, original, correction);
      MaterialProvisionFact before = credit.snapshotFact();
      correctAttribution(order, credit, original, correction, target);
      if (recordReceiptCorrection(credit, before, original) && target != null) {
        effects.receiptFactChanged(target);
      }
    } else if (correction.kind() == MaterialProvisionCorrectionDto.Kind.QUALITY) {
      correctQuality(order, credit, original, correction);
    } else {
      List<ReceiptBefore> before =
          connected == null
              ? List.of(new ReceiptBefore(credit, credit.snapshotFact(), original))
              : connected.facts().stream()
                  .map(fact ->
                      new ReceiptBefore(
                          fact.receipt(), fact.receipt().snapshotFact(), fact.claim()))
                  .toList();
      if (connected != null) {
        correctConnectedQuantity(connected);
      } else {
        correctExtraQuantity(credit, original, desired, added);
      }
      before.forEach(fact -> recordReceiptCorrection(fact.receipt(), fact.before(), fact.claim()));
    }
    coordinator.reconcileOrder(orderId);
    entityManager.flush();
    return history.read(orderId);
  }

  private boolean recordReceiptCorrection(
      @NotNull MaterialProvisionCredit receipt,
      @NotNull MaterialProvisionFact before,
      @NotNull MaterialClaim claim) {
    if (!receipt.recordFactCorrection(before)) {
      return false;
    }
    effects.receiptFactChanged(claim);
    return true;
  }

  /**
   * One authorized receipt declaration captured before a connected correction.
   *
   * @param receipt the managed receipt whose facts may change
   * @param before the authorized current fact snapshot
   * @param claim the affected retained contribution
   */
  private record ReceiptBefore(
      @NotNull MaterialProvisionCredit receipt,
      @NotNull MaterialProvisionFact before,
      @NotNull MaterialClaim claim) {}

  private @Nullable MaterialClaim attributionTarget(
      JobOrder order,
      MaterialProvisionCredit credit,
      MaterialClaim original,
      MaterialProvisionCorrectionDto correction) {
    return attributionTarget(
        order, credit, original, correction, credit.getSuppliedQuality(), false);
  }

  private @Nullable MaterialClaim attributionTarget(
      JobOrder order,
      MaterialProvisionCredit credit,
      MaterialClaim original,
      MaterialProvisionCorrectionDto correction,
      int suppliedQuality,
      boolean allowCurrent) {
    if (!Double.isFinite(correction.amount()) || correction.amount() < 0.0) {
      throw new BadRequestException("orders.claim.correction.amount_invalid");
    }
    long desired = MaterialQuantityArithmetic.quanta(correction.amount());
    long known = MaterialQuantityArithmetic.quanta(credit.getQuantityAmount());
    long quantum = original.getMaterial().getQuantityType() == QuantityType.PIECE ? 1000L : 1L;
    if (desired > known
        || desired % quantum != 0L
        || (correction.targetClaimId() == null && desired != 0L)
        || (correction.targetClaimId() != null
            && correction.targetClaimId().equals(original.getId())
            && !allowCurrent)) {
      throw new BadRequestException("orders.claim.correction.amount_invalid");
    }
    MaterialClaim target =
        correction.targetClaimId() == null ? null : historicalClaim(correction.targetClaimId());
    if (target != null) {
      authority.assertCanManage(order, target.getClaimingOrgUnit().getId());
      OptimisticLock.check(
          target.getVersion(),
          correction.targetClaimVersion(),
          MaterialClaim.class,
          target.getId());
      if (!target.getJobOrder().getId().equals(order.getId())
          || !target.getMaterial().getId().equals(credit.getMaterialId())
          || target.getQualityTier().getMinQuality() > suppliedQuality) {
        throw new BadRequestException("orders.claim.correction.not_matching");
      }
    }
    return target;
  }

  private void correctQuality(
      JobOrder order,
      MaterialProvisionCredit credit,
      MaterialClaim original,
      MaterialProvisionCorrectionDto correction) {
    int suppliedQuality = requiredQuality(correction);
    MaterialClaim target =
        attributionTarget(order, credit, original, correction, suppliedQuality, true);
    if (credit.isSuppliedQualityKnown()
        && credit.getSuppliedQuality() == suppliedQuality
        && credit.getAmount().doubleValue() == correction.amount()
        && (target == null ? credit.getAmount() == 0.0 : target.getId().equals(original.getId()))) {
      return;
    }
    MaterialProvisionFact before = credit.snapshotFact();
    credit.setSuppliedQuality(suppliedQuality);
    credit.setSuppliedQualityKnown(true);
    if (target != null && target.getId().equals(original.getId())) {
      correctRetainedAttribution(order, credit, original, correction, before);
    } else {
      correctAttribution(order, credit, original, correction, target);
    }
    if (recordReceiptCorrection(credit, before, original) && target != null) {
      effects.receiptFactChanged(target);
    }
  }

  private static int requiredQuality(MaterialProvisionCorrectionDto correction) {
    Integer quality = correction.suppliedQuality();
    if (quality == null
        || !de.greluc.krt.profit.basetool.backend.kernel.Quality.isInRange(quality)) {
      throw new BadRequestException("orders.claim.correction.quality_required");
    }
    return quality;
  }

  private void correctRetainedAttribution(
      JobOrder order,
      MaterialProvisionCredit credit,
      MaterialClaim original,
      MaterialProvisionCorrectionDto correction,
      MaterialProvisionFact before) {
    final double previous = original.getAmount();
    long desired = MaterialQuantityArithmetic.quanta(correction.amount());
    long old = MaterialQuantityArithmetic.quanta(credit.getAmount());
    if (desired != old) {
      selectAttribution(credit, desired);
      long delta = Math.subtractExact(desired, old);
      original.setCreditedAmount(
          fromQuanta(
              Math.addExact(
                  MaterialQuantityArithmetic.quanta(original.getCreditedAmount()), delta)));
      if (delta > 0L && original.getEndedAt() == null) {
        original.setAmount(
            fromQuanta(
                Math.max(
                    0L, Math.subtractExact(MaterialQuantityArithmetic.quanta(previous), delta))));
      } else if (delta < 0L) {
        long quantum = original.getMaterial().getQuantityType() == QuantityType.PIECE ? 1000L : 1L;
        restoreRunningPromise(order, original, Math.negateExact(delta), quantum);
      }
      credit.setAmount(fromQuanta(desired));
    }
    if (!credit.snapshotFact().equals(before)) {
      coordinator.record(
          original,
          MaterialContributionChange.Reason.FALSE_FACT_CORRECTED,
          previous,
          fromQuanta(Math.subtractExact(desired, old)),
          credit.getId());
    }
  }

  private void correctAttribution(
      JobOrder order,
      MaterialProvisionCredit credit,
      MaterialClaim original,
      MaterialProvisionCorrectionDto correction,
      @Nullable MaterialClaim target) {
    long desired = MaterialQuantityArithmetic.quanta(correction.amount());
    final long quantum =
        original.getMaterial().getQuantityType() == QuantityType.PIECE ? 1000L : 1L;
    selectAttribution(credit, desired);
    long old = MaterialQuantityArithmetic.quanta(credit.getAmount());
    final double beforeOriginal = original.getAmount();
    original.setCreditedAmount(
        fromQuanta(
            Math.subtractExact(
                MaterialQuantityArithmetic.quanta(original.getCreditedAmount()), old)));
    if (target != null) {
      double previous = target.getAmount();
      if (target.getEndedAt() == null) {
        target.setAmount(
            fromQuanta(
                Math.max(
                    0L, Math.subtractExact(MaterialQuantityArithmetic.quanta(previous), desired))));
      }
      target.setCreditedAmount(
          fromQuanta(
              MaterialQuantityArithmetic.add(
                  MaterialQuantityArithmetic.quanta(target.getCreditedAmount()), desired)));
      credit.setClaimId(target.getId());
      credit.setParticipationId(target.getParticipation().getId());
      credit.setClaimingOrgUnitId(target.getClaimingOrgUnit().getId());
      credit.setQualityTierId(target.getQualityTier().getId());
      coordinator.record(
          target,
          MaterialContributionChange.Reason.FALSE_FACT_CORRECTED,
          previous,
          fromQuanta(desired),
          credit.getId());
    }
    credit.setAmount(fromQuanta(desired));
    restoreRunningPromise(order, original, old, quantum);
    coordinator.record(
        original,
        MaterialContributionChange.Reason.FALSE_FACT_CORRECTED,
        beforeOriginal,
        fromQuanta(Math.negateExact(old)),
        credit.getId());
  }

  private void selectAttribution(MaterialProvisionCredit credit, long desired) {
    List<StockQuantityFragment> eligible = credits.availableForAttribution(credit);
    if (desired > StockQuantityFragments.total(eligible)) {
      throw new de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException(
          "orders.claim.correction.quantity_already_credited");
    }
    List<StockQuantityFragment> selection = StockQuantityFragments.prefix(eligible, desired);
    List<StockQuantityFragment> processed =
        credit.getFragments().stream()
            .map(fragment ->
                new StockQuantityFragment(
                    fragment.getOrigin(), fragment.getFromQuanta(), fragment.getToQuanta()))
            .toList();
    List<MaterialProvisionFragment> retained = new ArrayList<>(credit.getFragments());
    StockQuantityFragments.subtract(selection, processed)
        .forEach(fragment ->
            retained.add(
                new MaterialProvisionFragment(
                    fragment.origin(), fragment.fromQuanta(), fragment.toQuanta())));
    credit.setFragments(retained);
    credit.setAttributedFragments(selection);
  }

  private void correctConnectedQuantity(MaterialProvisionConnections.Plan plan) {
    List<CorrectionLoss> losses = new ArrayList<>();
    for (var fact : plan.facts()) {
      MaterialProvisionCredit receipt = fact.receipt();
      List<StockQuantityFragment> actual =
          StockQuantityFragments.subtract(receipt.getQuantityFragments(), plan.removed());
      List<StockQuantityFragment> attributed =
          StockQuantityFragments.subtract(receipt.getAttributedFragments(), plan.removed());
      if (actual.equals(receipt.getQuantityFragments())) {
        continue;
      }
      final long lost =
          Math.subtractExact(
              StockQuantityFragments.total(receipt.getAttributedFragments()),
              StockQuantityFragments.total(attributed));
      receipt.setQuantityFragments(actual);
      receipt.setQuantityAmount(fromQuanta(StockQuantityFragments.total(actual)));
      receipt.setAttributedFragments(attributed);
      receipt.setAmount(fromQuanta(StockQuantityFragments.total(attributed)));
      fact.claim()
          .setCreditedAmount(
              fromQuanta(
                  Math.subtractExact(
                      MaterialQuantityArithmetic.quanta(fact.claim().getCreditedAmount()), lost)));
      losses.add(new CorrectionLoss(fact, lost));
    }
    List<JobOrder> affectedOrders =
        plan.facts().stream()
            .map(MaterialProvisionConnections.Connected::order)
            .distinct()
            .toList();
    var capacities = capacity.ofOrders(affectedOrders);
    Map<PromiseBucket, Long> promised = new HashMap<>();
    contributions
        .findByJobOrderIdInOrderByCreatedAtDesc(
            affectedOrders.stream().map(JobOrder::getId).toList())
        .forEach(claim ->
            promised.merge(
                new PromiseBucket(
                    claim.getJobOrder().getId(),
                    claim.getMaterial().getId(),
                    claim.getQualityTier().getId()),
                MaterialQuantityArithmetic.quanta(claim.getAmount()),
                Math::addExact));
    losses.sort(
        Comparator.comparing(
                (CorrectionLoss loss) -> loss.fact().claim().getParticipation().getId())
            .thenComparing(loss -> loss.fact().receipt().getId()));
    for (CorrectionLoss loss : losses) {
      MaterialClaim claim = loss.fact().claim();
      double previous = claim.getAmount();
      var key =
          new PromiseBucket(
              claim.getJobOrder().getId(),
              claim.getMaterial().getId(),
              claim.getQualityTier().getId());
      var buckets = capacities.getOrDefault(key.orderId(), Map.of());
      var bucket = buckets.get(new MaterialPromiseCapacity.Bucket(key.materialId(), key.tierId()));
      if (claim.getEndedAt() == null && bucket != null) {
        long quantum = claim.getMaterial().getQuantityType() == QuantityType.PIECE ? 1000L : 1L;
        long outstanding = promised.getOrDefault(key, 0L);
        long room = Math.max(0L, Math.subtractExact(bucket.availableQuanta(), outstanding));
        long restore = Math.min(loss.quanta(), room) / quantum * quantum;
        claim.setAmount(
            fromQuanta(Math.addExact(MaterialQuantityArithmetic.quanta(previous), restore)));
        promised.put(key, Math.addExact(outstanding, restore));
      }
      coordinator.record(
          claim,
          MaterialContributionChange.Reason.FALSE_FACT_CORRECTED,
          previous,
          fromQuanta(Math.negateExact(loss.quanta())),
          loss.fact().receipt().getId());
    }
    affectedOrders.forEach(order -> coordinator.reconcileOrder(order.getId()));
  }

  /** One exact false credited quantity in a connected retained receipt. */
  private record CorrectionLoss(MaterialProvisionConnections.Connected fact, long quanta) {}

  /** One independently capacity-guarded material-quality promise bucket. */
  private record PromiseBucket(UUID orderId, UUID materialId, UUID tierId) {}

  private void correctExtraQuantity(
      MaterialProvisionCredit credit,
      MaterialClaim original,
      long desired,
      List<StockQuantityFragment> added) {
    long extra = StockQuantityFragments.total(added);
    long credited = MaterialQuantityArithmetic.quanta(original.getCreditedAmount());
    long promiseLimit = historicPromiseLimit(original);
    long fulfilled =
        credit.getAmount() > 0.0
            ? Math.min(extra, Math.max(0L, Math.subtractExact(promiseLimit, credited)))
            : 0L;
    double previous = original.getAmount();
    if (original.getEndedAt() == null) {
      original.setAmount(
          fromQuanta(
              Math.max(
                  0L, Math.subtractExact(MaterialQuantityArithmetic.quanta(previous), fulfilled))));
    }
    original.setCreditedAmount(fromQuanta(Math.addExact(credited, fulfilled)));
    credit.setAmount(
        fromQuanta(
            Math.addExact(MaterialQuantityArithmetic.quanta(credit.getAmount()), fulfilled)));
    credit.setQuantityAmount(fromQuanta(desired));
    List<StockQuantityFragment> actual = new ArrayList<>(credit.getQuantityFragments());
    actual.addAll(added);
    credit.setQuantityFragments(List.copyOf(actual));
    List<StockQuantityFragment> attributed = new ArrayList<>(credit.getAttributedFragments());
    attributed.addAll(StockQuantityFragments.prefix(added, fulfilled));
    credit.setAttributedFragments(List.copyOf(attributed));
    List<MaterialProvisionFragment> processed = new ArrayList<>(credit.getFragments());
    StockQuantityFragments.prefix(added, fulfilled)
        .forEach(fragment ->
            processed.add(
                new MaterialProvisionFragment(
                    fragment.origin(), fragment.fromQuanta(), fragment.toQuanta())));
    credit.setFragments(processed);
    coordinator.record(
        original,
        MaterialContributionChange.Reason.FALSE_FACT_CORRECTED,
        previous,
        fromQuanta(fulfilled),
        credit.getId());
  }

  private static long historicPromiseLimit(MaterialClaim claim) {
    long credit = 0L;
    long limit = 0L;
    for (MaterialContributionChange change : claim.getChanges()) {
      credit =
          Math.addExact(
              credit,
              de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts.toQuanta(
                  change.creditDelta()));
      limit =
          Math.max(
              limit, Math.addExact(MaterialQuantityArithmetic.quanta(change.amount()), credit));
    }
    return limit;
  }

  private static List<StockQuantityFragment> quantityOf(MaterialProvisionCredit credit) {
    return credit.getQuantityFragments();
  }

  private void restoreRunningPromise(
      JobOrder order, MaterialClaim original, long wronglyRemoved, long quantum) {
    if (original.getEndedAt() != null) {
      return;
    }
    var bucket =
        capacity
            .ofOrder(order)
            .get(
                new MaterialPromiseCapacity.Bucket(
                    original.getMaterial().getId(), original.getQualityTier().getId()));
    if (bucket == null) {
      return;
    }
    long promised =
        contributions
            .findByJobOrderIdAndMaterialIdAndQualityTierId(
                order.getId(), original.getMaterial().getId(), original.getQualityTier().getId())
            .stream()
            .mapToLong(c -> MaterialQuantityArithmetic.quanta(c.getAmount()))
            .reduce(0L, Math::addExact);
    long room = Math.max(0L, Math.subtractExact(bucket.availableQuanta(), promised));
    long restore = Math.min(wronglyRemoved, room) / quantum * quantum;
    original.setAmount(
        fromQuanta(
            MaterialQuantityArithmetic.add(
                MaterialQuantityArithmetic.quanta(original.getAmount()), restore)));
  }

  private MaterialClaim historicalClaim(UUID id) {
    return Entities.require(
        contributions.findHistoricalById(id), () -> "Material contribution not found: " + id);
  }
}
