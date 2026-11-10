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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

  /** The storage quanta in one whole material or game-item unit. */
  private static final long QUANTA_PER_UNIT = ScuAmounts.toQuanta(1.0);

  /** Non-instantiable static helper. */
  private AllocationReductions() {}

  /**
   * Resolves and validates one dimension's "deduct from" plan for deducting {@code totalX} from
   * {@code item}.
   *
   * <p>A {@code null} list takes unassigned rest first, then uses exact whole-unit largest
   * remainders for PIECE and game-item stock or three-decimal proportional shares for SCU. Complete
   * row deductions retain every actual slice.
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
      @Nullable List<AllocationReductionDto> reductions,
      double totalX,
      boolean jobOrderDimension) {
    if (reductions == null && item.countsWholeUnits()) {
      return defaultWholeReductionPlan(item, totalX, jobOrderDimension);
    }
    double amount = item.getAmount() != null ? item.getAmount() : 0.0;
    double sumSlices =
        jobOrderDimension
            ? InventoryAllocations.sumJobOrder(item)
            : InventoryAllocations.sumMission(item);
    double rest = ScuAmounts.roundToScuScale(amount - sumSlices);

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
    plan.forEach((targetId, scu) -> {
      if (jobOrderDimension) {
        InventoryAllocations.reduceJobOrder(item, targetId, scu);
      } else {
        InventoryAllocations.reduceMission(item, targetId, scu);
      }
    });
  }

  /**
   * Derives the SCU default: unassigned rest first, then proportional three-decimal shares with the
   * final tag absorbing rounding within its slice and residuals bounded by the required deduction.
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
    double forced = ScuAmounts.roundToScuScale(totalX - rest);
    if (forced <= REDUCTION_EPSILON) {
      return plan;
    }
    List<UUID> targets = new ArrayList<>();
    List<Double> sliceAmounts = new ArrayList<>();
    if (jobOrderDimension) {
      for (InventoryJobOrderAllocation a : item.getJobOrderAllocations()) {
        if (a.getJobOrderId() != null) {
          targets.add(a.getJobOrderId());
          sliceAmounts.add(a.getAmount() != null ? a.getAmount() : 0.0);
        }
      }
    } else {
      for (InventoryMissionAllocation a : item.getMissionAllocations()) {
        if (a.getMissionId() != null) {
          targets.add(a.getMissionId());
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
        share = ScuAmounts.roundToScuScale(forced - assigned);
      } else {
        share = ScuAmounts.roundToScuScale(forced * (sliceAmounts.get(i) / totalSlice));
      }
      share =
          Math.min(
              share, Math.min(sliceAmounts.get(i), ScuAmounts.roundToScuScale(forced - assigned)));
      if (share > 0.0) {
        plan.put(targets.get(i), share);
        assigned = ScuAmounts.roundToScuScale(assigned + share);
      }
    }
    completeScuRoundingResidual(
        plan, targets, sliceAmounts, ScuAmounts.roundToScuScale(forced - assigned));
    return plan;
  }

  /** Fills only a clipped final-tag residual without exceeding any existing slice. */
  private static void completeScuRoundingResidual(
      Map<UUID, Double> plan, List<UUID> targets, List<Double> sliceAmounts, double remaining) {
    for (int index = 0; index < targets.size() && remaining > REDUCTION_EPSILON; index++) {
      UUID target = targets.get(index);
      double assigned = plan.getOrDefault(target, 0.0);
      double available = ScuAmounts.roundToScuScale(sliceAmounts.get(index) - assigned);
      double share = Math.min(available, remaining);
      if (share > 0.0) {
        plan.put(target, ScuAmounts.roundToScuScale(assigned + share));
        remaining = ScuAmounts.roundToScuScale(remaining - share);
      }
    }
    if (remaining > REDUCTION_EPSILON) {
      throw new OverAllocationException();
    }
  }

  /**
   * Retains complete row deductions and apportions partial whole-unit shares by exact largest
   * remainders with target UUID ties.
   */
  private static Map<UUID, Double> defaultWholeReductionPlan(
      InventoryItem item, double totalX, boolean jobOrderDimension) {
    final Map<UUID, Double> earmarks =
        jobOrderDimension
            ? InventoryAllocations.jobOrderEarmarks(item)
            : InventoryAllocations.missionEarmarks(item);
    final double physicalAmount = item.getAmount() != null ? item.getAmount() : 0.0;
    if (Double.compare(totalX, physicalAmount) == 0) {
      return earmarks;
    }
    final long physicalQuanta = ScuAmounts.toQuanta(physicalAmount);
    if (physicalQuanta % QUANTA_PER_UNIT != 0L) {
      final long requestedQuanta = Math.multiplyExact(wholeUnits(item, totalX), QUANTA_PER_UNIT);
      final long taggedQuanta =
          earmarks.values().stream().mapToLong(ScuAmounts::toQuanta).reduce(0L, Math::addExact);
      if (requestedQuanta <= Math.subtractExact(physicalQuanta, taggedQuanta)) {
        return new LinkedHashMap<>();
      }
    }
    final Map<UUID, Long> quantities = new LinkedHashMap<>();
    earmarks.forEach((target, amount) -> quantities.put(target, wholeUnits(item, amount)));
    final long total =
        quantities.values().stream().mapToLong(Long::longValue).reduce(0L, Math::addExact);
    final long free =
        Math.subtractExact(
            wholeUnits(item, item.getAmount() != null ? item.getAmount() : 0.0), total);
    final long forced = Math.max(0L, Math.subtractExact(wholeUnits(item, totalX), free));
    if (free < 0L || forced > total) {
      throw new OverAllocationException();
    }
    final Map<UUID, Double> plan = new LinkedHashMap<>();
    if (forced == 0L) {
      return plan;
    }
    final BigInteger divisor = BigInteger.valueOf(total);
    final Map<UUID, Long> deductions = new LinkedHashMap<>();
    final List<WholeQuota> quotas = new ArrayList<>();
    quantities.forEach((target, amount) -> {
      BigInteger[] quota =
          BigInteger.valueOf(forced)
              .multiply(BigInteger.valueOf(amount))
              .divideAndRemainder(divisor);
      deductions.put(target, quota[0].longValueExact());
      quotas.add(new WholeQuota(target, quota[1].longValueExact()));
    });
    quotas.sort(
        Comparator.comparingLong(WholeQuota::remainder)
            .reversed()
            .thenComparing(WholeQuota::target));
    final long residual =
        Math.subtractExact(
            forced,
            deductions.values().stream().mapToLong(Long::longValue).reduce(0L, Math::addExact));
    for (int index = 0; index < residual; index++) {
      deductions.merge(quotas.get(index).target(), 1L, Math::addExact);
    }
    deductions.forEach((target, amount) -> {
      if (amount > 0L) {
        plan.put(target, ScuAmounts.fromQuanta(Math.multiplyExact(amount, QUANTA_PER_UNIT)));
      }
    });
    return plan;
  }

  /** Converts storage precision to exact integral units for a whole-unit row. */
  private static long wholeUnits(InventoryItem item, double amount) {
    long quanta = ScuAmounts.toQuanta(amount);
    if (quanta % QUANTA_PER_UNIT != 0L) {
      throw new BadRequestException(item.wholeUnitAmountDetail());
    }
    return quanta / QUANTA_PER_UNIT;
  }

  /** The exact fractional priority of one whole-unit proportional quota. */
  private record WholeQuota(UUID target, long remainder) {}

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
