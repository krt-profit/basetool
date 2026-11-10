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
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuditLabel;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialPromiseCapacity.Bucket;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import jakarta.persistence.EntityManager;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes each material's full quality scope and reconciles its final transaction state. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MaterialPromiseCoordinator {
  private static final int MATERIAL_RECONCILIATION_ORDER = 100;
  private final EntityManager entityManager;
  private final MaterialPromiseScopeRepository resources;
  private final de.greluc.krt.profit.basetool.backend.inventory.api.OrderLinkedStockRows
      stockScopes;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderRepository orders;
  private final MaterialClaimRepository claims;
  private final MaterialPromiseCapacity capacity;
  private final AuditRecorder audit;
  private final JobOrderChangeEffects effects;

  /**
   * Locks all named material scopes before Inventory lot and row locks.
   *
   * @param scopes the complete affected order-material set
   */
  public void lock(@NotNull Collection<OrderMaterialScope> scopes) {
    profitHierarchy.lockForOrderWrite();
    Coordination coordination = coordination();
    List<OrderMaterialScope> added =
        scopes.stream()
            .distinct()
            .filter(scope -> !coordination.scopes.contains(scope))
            .sorted(Comparator.comparingLong(MaterialPromiseCoordinator::lockKey))
            .toList();
    if (coordination.stockPhase && !added.isEmpty()) {
      throw new de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException(
          "error.inventory.concurrent_scope_changed");
    }
    for (OrderMaterialScope scope : added) {
      resources.lockResource(lockKey(scope));
      coordination.scopes.add(scope);
    }
  }

  /**
   * Locks an order's existing demand and historical promise materials before an aggregate edit.
   *
   * @param orderId the order
   */
  public void lockOrder(@NotNull UUID orderId) {
    lock(materialScopesOfOrder(orderId));
  }

  /**
   * Resolves all existing demand, history and shared-row material scopes before physical planning.
   *
   * @param orderId the order whose write resources are being planned
   * @return complete known scopes with the shared hierarchy stabilized, without material locks
   */
  public List<OrderMaterialScope> materialScopesOfOrder(@NotNull UUID orderId) {
    profitHierarchy.lockForOrderWrite();
    List<UUID> materialIds = resources.materialIdsOf(orderId);
    List<OrderMaterialScope> scopes =
        new ArrayList<>(stockScopes.materialScopesSharingRowsWithOrders(List.of(orderId)));
    materialIds.forEach(id -> scopes.add(new OrderMaterialScope(orderId, id)));
    return scopes.stream().distinct().toList();
  }

  /**
   * Reconciles an action's completed demand and stock changes before its response projection.
   *
   * @param orderId the order whose managed action has finished
   */
  public void reconcileOrder(@NotNull UUID orderId) {
    Set<OrderMaterialScope> scopes =
        coordination().scopes.stream()
            .filter(scope -> scope.orderId().equals(orderId))
            .collect(Collectors.toSet());
    reconcile(scopes);
  }

  /**
   * Serializes first participation insertion after all affected material locks are held.
   *
   * @param orderId the order
   * @param unitId the participating Staffel
   */
  public void lockParticipation(UUID orderId, UUID unitId) {
    resources.lockResource(
        UUID.nameUUIDFromBytes(
                ("order-participation/" + orderId + "/" + unitId).getBytes(StandardCharsets.UTF_8))
            .getMostSignificantBits());
  }

  /**
   * Validates actual stock scopes after stock locks without taking another material lock.
   *
   * @param scopes the scopes actually held by the locked stock rows
   */
  public void requireLocked(Collection<OrderMaterialScope> scopes) {
    Coordination current = coordination();
    if (!current.scopes.containsAll(scopes)) {
      throw new de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException(
          "error.inventory.concurrent_scope_changed");
    }
    current.stockPhase = true;
  }

  private Coordination coordination() {
    effects.prepare();
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof Coordination current) {
        return current;
      }
    }
    Coordination current = new Coordination();
    TransactionSynchronizationManager.registerSynchronization(current);
    return current;
  }

  private static long lockKey(OrderMaterialScope scope) {
    return UUID.nameUUIDFromBytes(
            ("material-promise/" + scope.orderId() + "/" + scope.materialId())
                .getBytes(StandardCharsets.UTF_8))
        .getMostSignificantBits();
  }

  private void reconcile(Set<OrderMaterialScope> scopes) {
    if (scopes.isEmpty()) {
      return;
    }
    entityManager.flush();
    Set<UUID> orderIds =
        scopes.stream().map(OrderMaterialScope::orderId).collect(Collectors.toSet());
    List<JobOrder> affected = orders.findAllById(orderIds);
    Map<UUID, Map<Bucket, MaterialPromiseCapacity.Capacity>> capacities =
        capacity.ofOrders(affected);
    List<MaterialClaim> active =
        claims.findByJobOrderIdInOrderByCreatedAtDesc(orderIds).stream()
            .filter(claim ->
                scopes.contains(
                    new OrderMaterialScope(
                        claim.getJobOrder().getId(), claim.getMaterial().getId())))
            .toList();
    for (OrderMaterialScope scope : scopes) {
      Map<UUID, List<MaterialClaim>> byTier =
          active.stream()
              .filter(claim ->
                  claim.getJobOrder().getId().equals(scope.orderId())
                      && claim.getMaterial().getId().equals(scope.materialId()))
              .collect(Collectors.groupingBy(claim -> claim.getQualityTier().getId()));
      for (var entry : byTier.entrySet()) {
        var bucket =
            capacities
                .getOrDefault(scope.orderId(), Map.of())
                .get(new Bucket(scope.materialId(), entry.getKey()));
        boolean terminal =
            entry.getValue().getFirst().getJobOrder().getStatus()
                    == de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus.COMPLETED
                || entry.getValue().getFirst().getJobOrder().getStatus()
                    == de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus.REJECTED;
        if (bucket == null || terminal) {
          for (MaterialClaim claim : entry.getValue()) {
            end(
                claim,
                terminal
                    ? MaterialContributionChange.Reason.ORDER_ENDED
                    : MaterialContributionChange.Reason.REQUIREMENT_ENDED);
          }
        } else {
          reduce(
              entry.getValue(),
              bucket.availableQuanta(),
              "PIECE".equals(bucket.material().quantityType()) ? 1000L : 1L);
        }
      }
    }
  }

  private void reduce(List<MaterialClaim> entries, long available, long quantum) {
    BigInteger sum =
        entries.stream()
            .map(claim ->
                BigInteger.valueOf(MaterialQuantityArithmetic.quanta(claim.getAmount()) / quantum))
            .reduce(BigInteger.ZERO, BigInteger::add);
    BigInteger target = BigInteger.valueOf(available / quantum);
    if (sum.compareTo(target) <= 0) {
      return;
    }
    List<Share> shares = new ArrayList<>();
    long assigned = 0L;
    for (MaterialClaim claim : entries) {
      BigInteger[] quota =
          BigInteger.valueOf(MaterialQuantityArithmetic.quanta(claim.getAmount()) / quantum)
              .multiply(target)
              .divideAndRemainder(sum);
      long units = quota[0].longValueExact();
      shares.add(new Share(claim, units, quota[1]));
      assigned = MaterialQuantityArithmetic.add(assigned, units);
    }
    shares.sort(
        Comparator.comparing(Share::remainder)
            .reversed()
            .thenComparing(share -> share.claim().getParticipation().getId()));
    long left = target.longValueExact() - assigned;
    for (int i = 0; i < shares.size(); i++) {
      Share share = shares.get(i);
      double amount =
          MaterialQuantityArithmetic.multiply(
                  MaterialQuantityArithmetic.add(share.units(), i < left ? 1L : 0L), quantum)
              / 1000.0;
      if (Double.compare(amount, share.claim().getAmount()) != 0) {
        double previous = share.claim().getAmount();
        share.claim().setAmount(amount);
        record(share.claim(), MaterialContributionChange.Reason.CAPACITY_REDUCED, previous, 0.0);
      }
    }
  }

  /**
   * Ends one open promise without ending the Staffel's participation.
   *
   * @param claim the managed contribution
   * @param reason the bounded ending cause
   */
  public void end(MaterialClaim claim, MaterialContributionChange.Reason reason) {
    if (claim.getEndedAt() != null) {
      return;
    }
    double previous = claim.getAmount();
    claim.setAmount(0.0);
    claim.setEndedAt(Instant.now());
    record(claim, reason, previous, 0.0, null, AuditEventType.JOB_ORDER_CLAIM_WITHDRAWN);
  }

  /**
   * Records a contribution change separately from current stock coverage.
   *
   * @param claim the managed contribution
   * @param reason the bounded reason
   * @param previous the preceding open quantity
   * @param creditDelta the historical fulfillment delta
   */
  public void record(
      MaterialClaim claim,
      MaterialContributionChange.Reason reason,
      double previous,
      double creditDelta) {
    record(claim, reason, previous, creditDelta, null);
  }

  /**
   * Records one quantity change with its connected historical provision identity.
   *
   * @param claim the managed contribution
   * @param reason bounded business reason
   * @param previous preceding open quantity
   * @param creditDelta historical fulfillment change
   * @param creditId connected provision, if any
   */
  public void record(
      MaterialClaim claim,
      MaterialContributionChange.Reason reason,
      double previous,
      double creditDelta,
      @Nullable UUID creditId) {
    record(claim, reason, previous, creditDelta, creditId, AuditEventType.JOB_ORDER_CLAIM_UPSERTED);
  }

  private void record(
      MaterialClaim claim,
      MaterialContributionChange.Reason reason,
      double previous,
      double creditDelta,
      @Nullable UUID creditId,
      AuditEventType auditType) {
    List<MaterialContributionChange> history = new ArrayList<>(claim.getChanges());
    history.add(
        new MaterialContributionChange(
            reason, previous, claim.getAmount(), creditDelta, Instant.now(), creditId));
    claim.setChanges(List.copyOf(history));
    audit.record(
        auditType,
        claim.getJobOrder().getId(),
        JobOrderAuditLabel.of(claim.getJobOrder()),
        null,
        AuditDetails.of("claim", claim.getId())
            .with("reason", reason.name())
            .with("material", claim.getMaterial().getId())
            .with("quality", claim.getQualityTier().getCode())
            .with("claimingOrgUnit", claim.getClaimingOrgUnit().getId())
            .with("previousAmount", previous)
            .with("amount", claim.getAmount())
            .with("creditDelta", creditDelta));
    effects.materialChanged(claim, reason, previous, creditDelta);
  }

  /** An exact proportional quota and its remainder. */
  private record Share(MaterialClaim claim, long units, BigInteger remainder) {}

  /** The material scopes guarded by the current transaction, including initially empty sets. */
  private final class Coordination implements TransactionSynchronization {
    private final Set<OrderMaterialScope> scopes = new LinkedHashSet<>();
    private boolean stockPhase;

    /** Reconciles final material facts before the grouped notice collector runs. */
    @Override
    public int getOrder() {
      return MATERIAL_RECONCILIATION_ORDER;
    }

    @Override
    public void beforeCommit(boolean readOnly) {
      if (!readOnly) {
        reconcile(new HashSet<>(scopes));
      }
    }
  }
}
