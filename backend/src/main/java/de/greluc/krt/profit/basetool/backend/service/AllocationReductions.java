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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import de.greluc.krt.profit.basetool.backend.model.dto.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Variante-C "deduct from" plan resolver (REQ-INV-027): given a quantity {@code totalX} leaving
 * an entry in one dimension (job-order or mission), it turns a client-supplied — or auto-derived —
 * plan into a validated {@code targetId → SCU} map naming how much of {@code totalX} comes out of
 * each earmark slice, with whatever is left over coming from that dimension's not-yet-assigned
 * rest.
 *
 * <p>Shared by every amount-lowering write that must keep the R5 invariant (Σ per dimension ≤ the
 * entry amount): the book-out / transfer deduct-from ({@code InventoryCheckoutService}) and the
 * job-order handover's mission clamp ({@code JobOrderHandoverService}) — a handover of {@code X} to
 * an order consumes {@code X} physical SCU that leave the mission earmark too, so the mission
 * dimension is reduced by exactly the same plan. Kept out of {@link InventoryAllocations} so that
 * class stays free of the web-mapped validation exceptions this one throws.
 */
public final class AllocationReductions {

  /**
   * Tolerance for SCU comparisons; amounts are SCU-rounded to three decimals first, so this only
   * absorbs floating-point noise.
   */
  public static final double REDUCTION_EPSILON = 1e-6;

  /** Non-instantiable static helper. */
  private AllocationReductions() {}

  /**
   * Resolves a single dimension's "deduct from" plan for a deduction of {@code totalX} from {@code
   * item}, validating it against the entry's pre-decrement slices. Returns an insertion-ordered
   * {@code targetId → SCU} map (empty = take it all from the dimension's not-yet-assigned rest).
   *
   * <p>A {@code null} list means "use the default": take from the rest first, then spread whatever
   * the rest cannot cover across the tags proportionally to their size — so the default never fails
   * and a caller that omits the plan keeps the legacy semantics. An explicit list is validated
   * exactly as given.
   *
   * @param item the entry whose slices back the plan (loaded within the tx); never {@code null}
   * @param reductions the requested reductions, or {@code null} to auto-derive the default plan
   * @param totalX the total quantity being deducted from the entry in this dimension
   * @param jobOrderDimension {@code true} for the job-order dimension, {@code false} for mission
   * @return the validated {@code targetId → amount} plan for this dimension
   * @throws BadRequestException when a reduction targets a non-earmarked slice, duplicates a
   *     target, exceeds its slice, or the reductions sum to more than {@code totalX}
   * @throws OverAllocationException when the plan under-assigns so much that the not-yet-assigned
   *     rest cannot absorb the remainder (the R5 422)
   */
  public static Map<UUID, Double> resolveReductionPlan(
      InventoryItem item,
      List<AllocationReductionDto> reductions,
      double totalX,
      boolean jobOrderDimension) {
    double amount = item.getAmount() != null ? item.getAmount() : 0.0;
    double sumSlices =
        jobOrderDimension
            ? InventoryAllocations.sumJobOrder(item)
            : InventoryAllocations.sumMission(item);
    double rest = InventoryItem.roundToScuScale(amount - sumSlices);

    if (reductions == null) {
      return defaultReductionPlan(item, totalX, rest, jobOrderDimension);
    }

    Map<UUID, Double> plan = new LinkedHashMap<>();
    double sumReductions = 0.0;
    for (AllocationReductionDto reduction : reductions) {
      double sliceAmount = sliceAmount(item, reduction.targetId(), jobOrderDimension);
      if (sliceAmount <= 0.0) {
        throw new BadRequestException("Cannot deduct from a target the entry is not earmarked to");
      }
      if (plan.put(reduction.targetId(), reduction.amount()) != null) {
        throw new BadRequestException("A target may appear at most once in the deduct-from plan");
      }
      if (reduction.amount() > sliceAmount + REDUCTION_EPSILON) {
        throw new BadRequestException("Cannot deduct more from a tag than it holds");
      }
      sumReductions += reduction.amount();
    }
    if (sumReductions > totalX + REDUCTION_EPSILON) {
      throw new BadRequestException("The deduct-from plan exceeds the deducted amount");
    }
    // The rest absorbs whatever the tags did not; if that is more than the rest holds, the plan is
    // under-assigned and cannot be applied without over-drawing the rest (the R5 422).
    if (totalX - sumReductions > rest + REDUCTION_EPSILON) {
      throw new OverAllocationException();
    }
    return plan;
  }

  /**
   * Applies a resolved single-dimension plan to the entry's slices, shrinking (and removing when
   * they hit zero) each tagged slice by its planned amount. Whatever the plan did not cover comes
   * from the dimension's rest once the caller lowers the entry amount.
   *
   * @param item the entry whose slices to shrink; never {@code null}
   * @param plan the resolved {@code targetId → SCU} plan for one dimension
   * @param jobOrderDimension {@code true} to shrink job-order slices, {@code false} for mission
   */
  public static void applyPlan(
      InventoryItem item, Map<UUID, Double> plan, boolean jobOrderDimension) {
    plan.forEach(
        (targetId, scu) -> {
          if (jobOrderDimension) {
            InventoryAllocations.reduceJobOrder(item, targetId, scu);
          } else {
            InventoryAllocations.reduceMission(item, targetId, scu);
          }
        });
  }

  /**
   * Auto-derives a dimension's plan when the caller omitted one: take {@code totalX} from the
   * not-yet-assigned rest first, then spread the remainder across the entry's tags in proportion to
   * their current amount (the last tag absorbs the rounding residue so the plan sums exactly).
   * Always yields an applyable plan.
   *
   * @param item the entry whose slices to spend against; never {@code null}
   * @param totalX the total quantity being deducted
   * @param rest the dimension's not-yet-assigned rest (already SCU-rounded)
   * @param jobOrderDimension {@code true} for job-order slices, {@code false} for mission slices
   * @return the derived {@code targetId → amount} plan (empty when the rest already covers {@code
   *     totalX})
   */
  private static Map<UUID, Double> defaultReductionPlan(
      InventoryItem item, double totalX, double rest, boolean jobOrderDimension) {
    Map<UUID, Double> plan = new LinkedHashMap<>();
    double forced = InventoryItem.roundToScuScale(totalX - rest);
    if (forced <= REDUCTION_EPSILON) {
      return plan; // the rest covers the whole deduction
    }
    List<UUID> targets = new ArrayList<>();
    List<Double> sliceAmounts = new ArrayList<>();
    if (jobOrderDimension) {
      for (InventoryJobOrderAllocation a : item.getJobOrderAllocations()) {
        if (a.getJobOrder() != null) {
          targets.add(a.getJobOrder().getId());
          sliceAmounts.add(a.getAmount() != null ? a.getAmount() : 0.0);
        }
      }
    } else {
      for (InventoryMissionAllocation a : item.getMissionAllocations()) {
        if (a.getMission() != null) {
          targets.add(a.getMission().getId());
          sliceAmounts.add(a.getAmount() != null ? a.getAmount() : 0.0);
        }
      }
    }
    double totalSlice = sliceAmounts.stream().mapToDouble(Double::doubleValue).sum();
    if (totalSlice <= 0.0) {
      return plan;
    }
    double assigned = 0.0;
    for (int i = 0; i < targets.size(); i++) {
      double share;
      if (i == targets.size() - 1) {
        share = InventoryItem.roundToScuScale(forced - assigned); // last tag takes the residue
      } else {
        share = InventoryItem.roundToScuScale(forced * (sliceAmounts.get(i) / totalSlice));
      }
      share = Math.min(share, sliceAmounts.get(i)); // never over-draw a tag
      if (share > 0.0) {
        plan.put(targets.get(i), share);
        assigned = InventoryItem.roundToScuScale(assigned + share);
      }
    }
    return plan;
  }

  /**
   * The pre-decrement slice amount of {@code targetId} in the given dimension, or {@code 0.0} when
   * the entry does not earmark it.
   *
   * @param item the entry; never {@code null}
   * @param targetId the job-order or mission id; never {@code null}
   * @param jobOrderDimension {@code true} for job-order slices, {@code false} for mission slices
   * @return the slice amount, or {@code 0.0} when not earmarked
   */
  private static double sliceAmount(InventoryItem item, UUID targetId, boolean jobOrderDimension) {
    if (jobOrderDimension) {
      InventoryJobOrderAllocation slice = InventoryAllocations.jobOrderSlice(item, targetId);
      return slice != null && slice.getAmount() != null ? slice.getAmount() : 0.0;
    }
    InventoryMissionAllocation slice = InventoryAllocations.missionSlice(item, targetId);
    return slice != null && slice.getAmount() != null ? slice.getAmount() : 0.0;
  }
}
