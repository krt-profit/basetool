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
import org.jetbrains.annotations.NotNull;

/**
 * Resolves "deduct from" plans for amount-lowering inventory writes (REQ-INV-027): how much of a
 * deducted quantity comes out of each job-order or mission earmark slice, the rest coming from the
 * dimension's unassigned remainder.
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
   * Resolves and validates one dimension's "deduct from" plan for deducting {@code totalX} from
   * {@code item}.
   *
   * <p>A {@code null} list derives the default plan (unassigned rest first, then proportionally
   * across the tags), which never fails.
   *
   * @param item the entry whose slices back the plan, loaded within the transaction; never {@code
   *     null}
   * @param reductions the requested reductions, or {@code null} for the default plan
   * @param totalX the total quantity being deducted in this dimension
   * @param jobOrderDimension {@code true} for the job-order dimension, {@code false} for mission
   * @return the insertion-ordered {@code targetId → amount} plan; empty means all from the rest
   * @throws BadRequestException when a reduction targets a non-earmarked slice, duplicates a
   *     target, exceeds its slice, or the reductions exceed {@code totalX}
   * @throws OverAllocationException when the unassigned rest cannot absorb the remainder
   */
  @NotNull
  public static Map<UUID, Double> resolveReductionPlan(
      @NotNull InventoryItem item,
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
    if (totalX - sumReductions > rest + REDUCTION_EPSILON) {
      throw new OverAllocationException();
    }
    return plan;
  }

  /**
   * Shrinks each tagged slice of the entry by its planned amount, removing slices that reach zero.
   *
   * @param item the entry whose slices to shrink; never {@code null}
   * @param plan the resolved {@code targetId → SCU} plan for one dimension
   * @param jobOrderDimension {@code true} to shrink job-order slices, {@code false} for mission
   */
  public static void applyPlan(
      InventoryItem item, @NotNull Map<UUID, Double> plan, boolean jobOrderDimension) {
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
   * Derives the default plan: {@code totalX} from the unassigned rest first, the remainder spread
   * across the tags in proportion to their amount, with the last tag absorbing rounding.
   *
   * @param item the entry whose slices to spend against; never {@code null}
   * @param totalX the total quantity being deducted
   * @param rest the dimension's unassigned rest, already SCU-rounded
   * @param jobOrderDimension {@code true} for job-order slices, {@code false} for mission slices
   * @return the derived {@code targetId → amount} plan; empty when the rest covers {@code totalX}
   */
  @NotNull
  private static Map<UUID, Double> defaultReductionPlan(
      InventoryItem item, double totalX, double rest, boolean jobOrderDimension) {
    Map<UUID, Double> plan = new LinkedHashMap<>();
    double forced = InventoryItem.roundToScuScale(totalX - rest);
    if (forced <= REDUCTION_EPSILON) {
      return plan;
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
        share = InventoryItem.roundToScuScale(forced - assigned);
      } else {
        share = InventoryItem.roundToScuScale(forced * (sliceAmounts.get(i) / totalSlice));
      }
      share = Math.min(share, sliceAmounts.get(i));
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
