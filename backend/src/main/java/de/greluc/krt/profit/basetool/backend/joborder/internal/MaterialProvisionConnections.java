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

import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Plans every known connected receipt before physical locks and rejects a changed dependency set.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MaterialProvisionConnections {
  private final MaterialProvisionCreditRepository receipts;
  private final MaterialClaimRepository contributions;
  private final JobOrderRepository orders;
  private final MaterialClaimAuthority authority;
  private final JobOrderResponsibilityGuard responsibility;
  private final EntityManager entityManager;
  private final ObjectMapper json;

  /**
   * Resolves all exact known receipt intersections and their current write prerequisites.
   *
   * @param original the explicitly corrected original receipt
   * @param removed exact physical intervals declared false
   * @return the complete pre-lock dependency plan
   */
  public Plan prepare(MaterialProvisionCredit original, List<StockQuantityFragment> removed) {
    String quantity = json.writeValueAsString(removed);
    Set<UUID> intersections = intersecting(quantity, removed);
    Set<UUID> ids = new HashSet<>(intersections);
    ids.add(original.getId());
    List<MaterialProvisionCredit> facts = receipts.findByIdInOrderById(ids);
    Map<UUID, MaterialClaim> claims =
        contributions
            .findHistoricalByIdIn(
                facts.stream().map(MaterialProvisionCredit::getClaimId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(MaterialClaim::getId, Function.identity()));
    Map<UUID, JobOrder> jobs =
        orders
            .findAllById(
                facts.stream().map(MaterialProvisionCredit::getOrderId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(JobOrder::getId, Function.identity()));
    if (facts.size() != ids.size()
        || !jobs.keySet()
            .containsAll(facts.stream().map(MaterialProvisionCredit::getOrderId).toList())
        || !claims
            .keySet()
            .containsAll(facts.stream().map(MaterialProvisionCredit::getClaimId).toList())) {
      throw changed();
    }
    requireValid(jobs.values());
    List<Connected> connected =
        facts.stream()
            .map(receipt -> {
              MaterialClaim claim = claims.get(receipt.getClaimId());
              JobOrder order = jobs.get(receipt.getOrderId());
              if (claim == null || order == null) {
                throw changed();
              }
              authority.assertCanManage(order, claim.getClaimingOrgUnit().getId());
              return new Connected(receipt, claim, order, receipt.getVersion(), claim.getVersion());
            })
            .toList();
    return new Plan(connected, List.copyOf(removed), Set.copyOf(intersections), quantity);
  }

  /**
   * Rechecks the complete dependency set after physical locks without acquiring another scope.
   *
   * @param plan the original complete plan
   */
  public void revalidate(Plan plan) {
    if (!intersecting(plan.quantity(), plan.removed()).equals(plan.intersections())) {
      throw changed();
    }
    Map<UUID, Long> receiptVersions =
        versions(
            receipts.currentVersions(plan.facts().stream().map(f -> f.receipt().getId()).toList()));
    Map<UUID, Long> claimVersions =
        versions(
            contributions.currentVersions(
                plan.facts().stream().map(f -> f.claim().getId()).distinct().toList()));
    Set<JobOrder> current = plan.facts().stream().map(Connected::order).collect(Collectors.toSet());
    current.forEach(entityManager::refresh);
    requireValid(current);
    for (Connected fact : plan.facts()) {
      if (!java.util.Objects.equals(
              receiptVersions.get(fact.receipt().getId()), fact.receiptVersion())
          || !java.util.Objects.equals(
              claimVersions.get(fact.claim().getId()), fact.claimVersion())) {
        throw changed();
      }
      authority.assertCanManage(fact.order(), fact.claim().getClaimingOrgUnit().getId());
    }
  }

  private Set<UUID> intersecting(String quantity, List<StockQuantityFragment> removed) {
    return removed.isEmpty() ? Set.of() : Set.copyOf(receipts.findIntersectingReceiptIds(quantity));
  }

  private void requireValid(Collection<JobOrder> jobs) {
    if (responsibility.validOrderIds(jobs).size() != jobs.size()) {
      throw new BadRequestException("orders.responsibility.invalid");
    }
  }

  private static Map<UUID, Long> versions(List<MaterialFactVersion> facts) {
    return facts.stream()
        .collect(Collectors.toMap(MaterialFactVersion::getId, MaterialFactVersion::getVersion));
  }

  private static BusinessConflictException changed() {
    return new BusinessConflictException("orders.claim.correction.connected_facts_changed");
  }

  /**
   * A managed connected receipt and the immutable versions observed before any physical lock.
   *
   * @param receipt the explicit known receipt
   * @param claim its retained contribution
   * @param order its current concrete order
   * @param receiptVersion preplanned receipt version
   * @param claimVersion preplanned contribution version
   */
  public record Connected(
      MaterialProvisionCredit receipt,
      MaterialClaim claim,
      JobOrder order,
      Long receiptVersion,
      Long claimVersion) {}

  /**
   * The full immutable set of connected receipt prerequisites for one physical correction.
   *
   * @param facts retained connected facts including the selected original
   * @param removed exact false physical intervals
   * @param intersections identities intersecting these intervals before physical locks
   * @param quantity serialized exact intervals used by the owning repository query
   */
  public record Plan(
      List<Connected> facts,
      List<StockQuantityFragment> removed,
      Set<UUID> intersections,
      String quantity) {
    /**
     * Returns the complete connected material scope set.
     *
     * @return every connected order-material guard required before physical locks
     */
    public List<OrderMaterialScope> scopes() {
      return facts.stream()
          .map(fact ->
              new OrderMaterialScope(fact.receipt().getOrderId(), fact.receipt().getMaterialId()))
          .distinct()
          .toList();
    }
  }
}
