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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Helpers that build and adjust an inventory entry's job-order and mission allocation slices
 * (REQ-INV-027), shared by every inventory write path.
 *
 * <p>Per dimension, the sum of slice amounts must stay within the entry's amount; amount-lowering
 * paths check this with {@link #fits(InventoryItem)}.
 */
public final class InventoryAllocations {

  /**
   * Tolerance for the R5 fit comparison. Both the entry amount and every slice amount are
   * SCU-rounded to three decimals at the persistence boundary, so a Σ that lands a hair over the
   * entry's amount purely through {@code double} noise is tolerated; anything beyond is a genuine
   * over-allocation.
   */
  private static final double EPSILON = 1e-6;

  private InventoryAllocations() {}

  /**
   * Sums the amounts of the entry's job-order slices.
   *
   * @param item the entry (its job-order allocations must be loaded within the tx); never {@code
   *     null}
   * @return the total SCU earmarked to job orders
   */
  public static double sumJobOrder(@NotNull InventoryItem item) {
    return item.getJobOrderAllocations().stream()
        .mapToDouble(a -> a.getAmount() != null ? a.getAmount() : 0.0)
        .sum();
  }

  /**
   * Sums the amounts of the entry's mission slices.
   *
   * @param item the entry (its mission allocations must be loaded within the tx); never {@code
   *     null}
   * @return the total SCU earmarked to missions
   */
  public static double sumMission(@NotNull InventoryItem item) {
    return item.getMissionAllocations().stream()
        .mapToDouble(a -> a.getAmount() != null ? a.getAmount() : 0.0)
        .sum();
  }

  /**
   * Checks, epsilon-tolerantly, whether the entry's amount still covers both allocation sums; a
   * decrement path rejects the write (422) when it does not.
   *
   * @param item the entry with its post-decrement amount and allocations loaded; never {@code null}
   * @return {@code true} when Σ(job) ≤ amount and Σ(mission) ≤ amount within {@link #EPSILON}
   */
  public static boolean fits(@NotNull InventoryItem item) {
    double amount = item.getAmount() != null ? item.getAmount() : 0.0;
    return sumJobOrder(item) <= amount + EPSILON && sumMission(item) <= amount + EPSILON;
  }

  /**
   * Returns the entry version a client must echo after an {@code OPTIMISTIC_FORCE_INCREMENT} write,
   * i.e. the loaded version plus one, since Hibernate applies the increment only at commit.
   *
   * @param saved the just-flushed entry, still at its pre-increment version; never {@code null}
   * @return the version the client should echo on its next write
   */
  public static long forcedNextVersion(@NotNull InventoryItem saved) {
    return (saved.getVersion() != null ? saved.getVersion() : 0L) + 1L;
  }

  /**
   * Appends a job-order slice earmarking {@code amount} of the entry; the caller owns the sum and
   * duplicate-target guards.
   *
   * @param item the owning entry; never {@code null}
   * @param order the earmarked job order; never {@code null}
   * @param amount the SCU to earmark
   * @param delivered the initial delivered state
   * @return the created slice, already added to the entry
   */
  @NotNull
  public static InventoryJobOrderAllocation addJobOrder(
      InventoryItem item, JobOrder order, double amount, boolean delivered) {
    InventoryJobOrderAllocation slice = new InventoryJobOrderAllocation();
    slice.setInventoryItem(item);
    slice.setJobOrder(order);
    slice.setAmount(amount);
    slice.setDelivered(delivered);
    item.getJobOrderAllocations().add(slice);
    return slice;
  }

  /**
   * Appends a mission slice earmarking {@code amount} of the entry.
   *
   * @param item the owning entry; never {@code null}
   * @param mission the earmarked mission; never {@code null}
   * @param amount the SCU to earmark
   * @return the created slice, already added to the entry
   */
  @NotNull
  public static InventoryMissionAllocation addMission(
      InventoryItem item, Mission mission, double amount) {
    InventoryMissionAllocation slice = new InventoryMissionAllocation();
    slice.setInventoryItem(item);
    slice.setMission(mission);
    slice.setAmount(amount);
    item.getMissionAllocations().add(slice);
    return slice;
  }

  /**
   * Shrinks the entry's job-order slice by the handed-over {@code amount}, removing it at zero; a
   * missing slice is a no-op.
   *
   * @param item the entry; never {@code null}
   * @param orderId the fulfilled job order; never {@code null}
   * @param amount the handed-over SCU to subtract
   */
  public static void reduceJobOrder(InventoryItem item, UUID orderId, double amount) {
    InventoryJobOrderAllocation slice = jobOrderSlice(item, orderId);
    if (slice == null) {
      return;
    }
    double reduced = (slice.getAmount() != null ? slice.getAmount() : 0.0) - amount;
    if (InventoryItem.roundToScuScale(reduced) <= 0.0) {
      item.getJobOrderAllocations().remove(slice);
    } else {
      slice.setAmount(reduced);
    }
  }

  /**
   * Shrinks the entry's mission slice by {@code amount}, removing it once it would round to zero; a
   * missing slice is a no-op.
   *
   * @param item the entry; never {@code null}
   * @param missionId the earmarked mission; never {@code null}
   * @param amount the SCU to subtract
   */
  public static void reduceMission(InventoryItem item, UUID missionId, double amount) {
    InventoryMissionAllocation slice = missionSlice(item, missionId);
    if (slice == null) {
      return;
    }
    double reduced = (slice.getAmount() != null ? slice.getAmount() : 0.0) - amount;
    if (InventoryItem.roundToScuScale(reduced) <= 0.0) {
      item.getMissionAllocations().remove(slice);
    } else {
      slice.setAmount(reduced);
    }
  }

  /**
   * Unions {@code victim}'s allocations into {@code survivor} for a stock merge: slices for the
   * same target are summed with delivered OR-combined, others are added.
   *
   * <p>The caller must already have added the victim's amount to the survivor.
   *
   * @param survivor the surviving entry; never {@code null}
   * @param victim the folded entry; never {@code null}
   */
  public static void unionInto(InventoryItem survivor, InventoryItem victim) {
    for (InventoryJobOrderAllocation va : victim.getJobOrderAllocations()) {
      UUID orderId = va.getJobOrder() != null ? va.getJobOrder().getId() : null;
      InventoryJobOrderAllocation existing = jobOrderSlice(survivor, orderId);
      if (existing != null) {
        existing.setAmount(existing.getAmount() + va.getAmount());
        existing.setDelivered(
            Boolean.TRUE.equals(existing.getDelivered()) || Boolean.TRUE.equals(va.getDelivered()));
      } else {
        addJobOrder(
            survivor, va.getJobOrder(), va.getAmount(), Boolean.TRUE.equals(va.getDelivered()));
      }
    }
    for (InventoryMissionAllocation va : victim.getMissionAllocations()) {
      UUID missionId = va.getMission() != null ? va.getMission().getId() : null;
      InventoryMissionAllocation existing = missionSlice(survivor, missionId);
      if (existing != null) {
        existing.setAmount(existing.getAmount() + va.getAmount());
      } else {
        addMission(survivor, va.getMission(), va.getAmount());
      }
    }
  }

  /**
   * Finds the entry's job-order slice for {@code orderId}.
   *
   * @param item the entry; never {@code null}
   * @param orderId the job-order id to match; may be {@code null}
   * @return the matching slice, or {@code null} when none
   */
  @Nullable
  public static InventoryJobOrderAllocation jobOrderSlice(InventoryItem item, UUID orderId) {
    if (orderId == null) {
      return null;
    }
    return item.getJobOrderAllocations().stream()
        .filter(a -> a.getJobOrder() != null && orderId.equals(a.getJobOrder().getId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Finds the entry's mission slice for {@code missionId}.
   *
   * @param item the entry; never {@code null}
   * @param missionId the mission id to match; may be {@code null}
   * @return the matching slice, or {@code null} when none
   */
  @Nullable
  public static InventoryMissionAllocation missionSlice(InventoryItem item, UUID missionId) {
    if (missionId == null) {
      return null;
    }
    return item.getMissionAllocations().stream()
        .filter(a -> a.getMission() != null && missionId.equals(a.getMission().getId()))
        .findFirst()
        .orElse(null);
  }
}
