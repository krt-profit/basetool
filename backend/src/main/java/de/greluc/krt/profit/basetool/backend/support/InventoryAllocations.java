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

/**
 * Allocation-collection helpers for the write paths of the Variante-C inventory model
 * (REQ-INV-027). Since the scalar {@code jobOrder}/{@code mission}/{@code delivered} columns and
 * the soak mirror were dropped (V218), every write path builds an entry's {@link
 * InventoryItem#getJobOrderAllocations()} / {@link InventoryItem#getMissionAllocations()} slices
 * directly through the methods here — the single implementation that create, refinery deposit,
 * transfer / personal-rebook (full-move inherit) and the stock merge (union) all share, replacing
 * the former {@code InventoryAllocationSync} scalar mirror.
 *
 * <p>The R5 invariant is that, per dimension, the Σ of the slice amounts must stay within the
 * entry's own amount; {@link #fits(InventoryItem)} is the guard the amount-lowering paths (book-out
 * consume, transfer / rebook source remainder, handover) call before committing a decrement.
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
  public static double sumJobOrder(InventoryItem item) {
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
  public static double sumMission(InventoryItem item) {
    return item.getMissionAllocations().stream()
        .mapToDouble(a -> a.getAmount() != null ? a.getAmount() : 0.0)
        .sum();
  }

  /**
   * Reports whether the entry's amount still covers both dimension sums (rule R5),
   * epsilon-tolerant. A decrement path calls this after lowering {@link InventoryItem#getAmount()}
   * and rejects the write (422) when it returns {@code false}, so the user reduces the allocations
   * first.
   *
   * @param item the entry with its (post-decrement) amount and its allocations loaded; never {@code
   *     null}
   * @return {@code true} when Σ(job) ≤ amount and Σ(mission) ≤ amount within {@link #EPSILON}
   */
  public static boolean fits(InventoryItem item) {
    double amount = item.getAmount() != null ? item.getAmount() : 0.0;
    return sumJobOrder(item) <= amount + EPSILON && sumMission(item) <= amount + EPSILON;
  }

  /**
   * Appends a job-order slice earmarking {@code amount} of the entry to {@code order}. The caller
   * owns the R5 / duplicate-target guards; this only wires the managed child so cascade-persist
   * inserts it on flush.
   *
   * @param item the owning entry; never {@code null}
   * @param order the earmarked job order; never {@code null}
   * @param amount the SCU to earmark
   * @param delivered the initial delivered state of the slice
   * @return the created slice, already added to the entry's collection
   */
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
   * Appends a mission slice earmarking {@code amount} of the entry to {@code mission} (missions
   * carry no delivered marker).
   *
   * @param item the owning entry; never {@code null}
   * @param mission the earmarked mission; never {@code null}
   * @param amount the SCU to earmark
   * @return the created slice, already added to the entry's collection
   */
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
   * Copies every allocation of {@code from} onto {@code to} verbatim — the full-move inherit for a
   * transfer / personal-rebook that relocates an entry's <em>entire</em> stock (the moved row is a
   * pure relocation, so amounts already match and no split is needed). A partial move does NOT call
   * this: its moved row starts unassigned (a multi-order earmark cannot be split), and the source
   * keeps its allocations guarded by {@link #fits(InventoryItem)}.
   *
   * @param from the source entry whose allocations to copy; never {@code null}
   * @param to the target (moved) entry to copy them onto; never {@code null}
   */
  public static void copyAllocations(InventoryItem from, InventoryItem to) {
    for (InventoryJobOrderAllocation a : from.getJobOrderAllocations()) {
      addJobOrder(to, a.getJobOrder(), a.getAmount(), Boolean.TRUE.equals(a.getDelivered()));
    }
    for (InventoryMissionAllocation a : from.getMissionAllocations()) {
      addMission(to, a.getMission(), a.getAmount());
    }
  }

  /**
   * Shrinks the entry's earmark to {@code orderId} by {@code amount} because that much stock was
   * just handed over to (i.e. fulfilled for) the order and physically left inventory. The slice is
   * floored at zero and removed entirely when it reaches it (no zero-amount chip lingers); a
   * missing slice — the handed stock was unassigned — is a no-op.
   *
   * @param item the entry whose job-order slice to shrink; never {@code null}
   * @param orderId the fulfilled job order; never {@code null}
   * @param amount the handed-over SCU to subtract from the slice
   */
  public static void reduceJobOrder(InventoryItem item, UUID orderId, double amount) {
    InventoryJobOrderAllocation slice = findJobOrder(item, orderId);
    if (slice == null) {
      return;
    }
    double reduced = (slice.getAmount() != null ? slice.getAmount() : 0.0) - amount;
    // Remove at the SCU rounding boundary, not at EPSILON: a residual in (EPSILON, 5e-4) —
    // reachable
    // from a >3-decimal SCU handover amount — would survive an EPSILON test yet round to 0.000 on
    // the slice's @PreUpdate flush, persisting a phantom zero-amount chip that then blocks
    // re-earmarking the order (the unique (item, order) slot is taken). Dropping the slice whenever
    // it would round to zero keeps that from happening.
    if (InventoryItem.roundToScuScale(reduced) <= 0.0) {
      item.getJobOrderAllocations().remove(slice);
    } else {
      slice.setAmount(reduced);
    }
  }

  /**
   * Unions {@code victim}'s allocations into {@code survivor} for the stock merge (R1): a slice for
   * an order / mission the survivor already earmarks is summed into the existing slice (the
   * per-dimension unique constraint allows only one slice per target), and job-order delivered is
   * OR-combined (delivered if <em>either</em> folded part was); an unmatched slice is added fresh.
   * The survivor's amount is summed by the caller before this runs, so the R5 invariant Σ ≤ amount
   * is preserved under the fold.
   *
   * @param survivor the surviving entry whose amount already absorbed the victims; never {@code
   *     null}
   * @param victim the folded entry whose allocations to union in; never {@code null}
   */
  public static void unionInto(InventoryItem survivor, InventoryItem victim) {
    for (InventoryJobOrderAllocation va : victim.getJobOrderAllocations()) {
      UUID orderId = va.getJobOrder() != null ? va.getJobOrder().getId() : null;
      InventoryJobOrderAllocation existing = findJobOrder(survivor, orderId);
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
      InventoryMissionAllocation existing = findMission(survivor, missionId);
      if (existing != null) {
        existing.setAmount(existing.getAmount() + va.getAmount());
      } else {
        addMission(survivor, va.getMission(), va.getAmount());
      }
    }
  }

  /**
   * Finds the entry's job-order slice for {@code orderId}, or {@code null}.
   *
   * @param item the entry; never {@code null}
   * @param orderId the job-order id to match; may be {@code null}
   * @return the matching slice, or {@code null} when none
   */
  private static InventoryJobOrderAllocation findJobOrder(InventoryItem item, UUID orderId) {
    if (orderId == null) {
      return null;
    }
    return item.getJobOrderAllocations().stream()
        .filter(a -> a.getJobOrder() != null && orderId.equals(a.getJobOrder().getId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Finds the entry's mission slice for {@code missionId}, or {@code null}.
   *
   * @param item the entry; never {@code null}
   * @param missionId the mission id to match; may be {@code null}
   * @return the matching slice, or {@code null} when none
   */
  private static InventoryMissionAllocation findMission(InventoryItem item, UUID missionId) {
    if (missionId == null) {
      return null;
    }
    return item.getMissionAllocations().stream()
        .filter(a -> a.getMission() != null && missionId.equals(a.getMission().getId()))
        .findFirst()
        .orElse(null);
  }
}
