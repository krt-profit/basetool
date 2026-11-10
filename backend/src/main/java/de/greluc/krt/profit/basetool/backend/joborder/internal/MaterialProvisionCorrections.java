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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Corrects false original contribution facts without treating genuine later loss as an inverse. */
@Service
@RequiredArgsConstructor
public class MaterialProvisionCorrections {
  private final MaterialProvisionCreditRepository provisions;
  private final MaterialProvisionConnections connections;
  private final StockQuantityCorrections stockCorrections;
  private final MaterialClaimRepository contributions;
  private final JobOrderRepository orders;
  private final MaterialClaimAuthority authority;
  private final JobOrderResponsibilityGuard responsibility;
  private final MaterialPromiseCoordinator coordinator;
  private final MaterialPromiseCapacity capacity;
  private final MaterialContributionHistory history;
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
      correctAttribution(order, credit, original, correction);
    } else {
      if (connected != null) {
        correctConnectedQuantity(connected);
      } else {
        correctExtraQuantity(credit, original, desired, added);
      }
    }
    coordinator.reconcileOrder(orderId);
    entityManager.flush();
    return history.read(orderId);
  }

  private void correctAttribution(
      JobOrder order,
      MaterialProvisionCredit credit,
      MaterialClaim original,
      MaterialProvisionCorrectionDto correction) {
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
            && correction.targetClaimId().equals(original.getId()))) {
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
          || target.getQualityTier().getMinQuality() > credit.getSuppliedQuality()) {
        throw new BadRequestException("orders.claim.correction.not_matching");
      }
    }
    var foreign =
        provisions.processedQuantityExcept(
            credit.getQuantityFragments().stream()
                .map(StockQuantityFragment::origin)
                .distinct()
                .toList(),
            credit.getId());
    List<StockQuantityFragment> eligible =
        StockQuantityFragments.subtract(credit.getQuantityFragments(), foreign);
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
    long old = MaterialQuantityArithmetic.quanta(credit.getAmount());
    final double beforeOriginal = original.getAmount();
    original.setCreditedAmount(
        Math.subtractExact(MaterialQuantityArithmetic.quanta(original.getCreditedAmount()), old)
            / 1000.0);
    if (target != null) {
      double previous = target.getAmount();
      if (target.getEndedAt() == null) {
        target.setAmount(
            Math.max(0L, Math.subtractExact(MaterialQuantityArithmetic.quanta(previous), desired))
                / 1000.0);
      }
      target.setCreditedAmount(
          MaterialQuantityArithmetic.add(
                  MaterialQuantityArithmetic.quanta(target.getCreditedAmount()), desired)
              / 1000.0);
      credit.setClaimId(target.getId());
      credit.setParticipationId(target.getParticipation().getId());
      credit.setClaimingOrgUnitId(target.getClaimingOrgUnit().getId());
      credit.setQualityTierId(target.getQualityTier().getId());
      coordinator.record(
          target,
          MaterialContributionChange.Reason.FALSE_FACT_CORRECTED,
          previous,
          desired / 1000.0,
          credit.getId());
    }
    credit.setAmount(desired / 1000.0);
    restoreRunningPromise(order, original, old, quantum);
    coordinator.record(
        original,
        MaterialContributionChange.Reason.FALSE_FACT_CORRECTED,
        beforeOriginal,
        -old / 1000.0,
        credit.getId());
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
      receipt.setQuantityAmount(StockQuantityFragments.total(actual) / 1000.0);
      receipt.setAttributedFragments(attributed);
      receipt.setAmount(StockQuantityFragments.total(attributed) / 1000.0);
      fact.claim()
          .setCreditedAmount(
              Math.subtractExact(
                      MaterialQuantityArithmetic.quanta(fact.claim().getCreditedAmount()), lost)
                  / 1000.0);
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
            Math.addExact(MaterialQuantityArithmetic.quanta(previous), restore) / 1000.0);
        promised.put(key, Math.addExact(outstanding, restore));
      }
      coordinator.record(
          claim,
          MaterialContributionChange.Reason.FALSE_FACT_CORRECTED,
          previous,
          -loss.quanta() / 1000.0,
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
          Math.max(0L, Math.subtractExact(MaterialQuantityArithmetic.quanta(previous), fulfilled))
              / 1000.0);
    }
    original.setCreditedAmount(Math.addExact(credited, fulfilled) / 1000.0);
    credit.setAmount(
        Math.addExact(MaterialQuantityArithmetic.quanta(credit.getAmount()), fulfilled) / 1000.0);
    credit.setQuantityAmount(desired / 1000.0);
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
        fulfilled / 1000.0,
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
        MaterialQuantityArithmetic.add(
                MaterialQuantityArithmetic.quanta(original.getAmount()), restore)
            / 1000.0);
  }

  private MaterialClaim historicalClaim(UUID id) {
    return Entities.require(
        contributions.findHistoricalById(id), () -> "Material contribution not found: " + id);
  }
}
