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

import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Stateless math behind the operation payout breakdown: turns already-loaded finance entries,
 * refinery orders and the participant graph into the total sum, per-participant out-of-pocket
 * reimbursements and the attendance breakdown. Uses no repository or security context; the only
 * clock read is {@link Instant#now()} for clamping still-running participants.
 */
public final class OperationPayoutCalculator {

  /** Utility class — only static math, never instantiated. */
  private OperationPayoutCalculator() {}

  /**
   * Computes the operation total sum (mission INCOME plus refinery profit minus mission EXPENSE),
   * identical to the {@code OperationFinanceService} figure.
   *
   * @param entries the mission finance entries across every mission of the operation
   * @param orders the refinery orders across every mission of the operation
   * @return the operation-wide total sum (income + refinery profit − expense)
   */
  @NotNull
  public static BigDecimal computeTotalSum(
      @NotNull List<MissionFinanceEntry> entries, @NotNull List<RefineryOrder> orders) {
    BigDecimal total = BigDecimal.ZERO;
    for (MissionFinanceEntry entry : entries) {
      if (entry.getType() == FinanceType.INCOME) {
        total = total.add(entry.getAmount());
      } else if (entry.getType() == FinanceType.EXPENSE) {
        total = total.subtract(entry.getAmount());
      }
    }
    for (RefineryOrder order : orders) {
      double sales = order.getOreSales() != null ? order.getOreSales() : 0d;
      double costs = order.getExpenses() != null ? order.getExpenses() : 0d;
      double otherCosts = order.getOtherExpenses() != null ? order.getOtherExpenses() : 0d;
      double profit = sales - costs - otherCosts;
      if (profit != 0d) {
        total = total.add(BigDecimal.valueOf(profit));
      }
    }
    return total;
  }

  /**
   * Sums each participant's out-of-pocket expenses across the whole operation: mission EXPENSE
   * entries where they are the {@code participant} plus refinery orders' {@code expenses +
   * otherExpenses} where they are the {@code owner}. Refinery sales accrue to the operation pool
   * (see {@link #computeTotalSum}) — the owner only gets back the costs they advanced. INCOME
   * entries are not attributed to any single participant; they belong to the shared pool.
   *
   * @param entries the mission finance entries across every mission of the operation
   * @param orders the refinery orders across every mission of the operation
   * @return per-participant-key sum of reimbursable out-of-pocket expenses
   */
  @NotNull
  public static Map<String, BigDecimal> computePersonalExpensesByParticipant(
      @NotNull List<MissionFinanceEntry> entries, @NotNull List<RefineryOrder> orders) {
    Map<String, BigDecimal> byKey = new HashMap<>();
    for (MissionFinanceEntry entry : entries) {
      if (entry.getType() != FinanceType.EXPENSE) {
        continue;
      }
      MissionParticipant participant = entry.getParticipant();
      if (participant == null) {
        continue;
      }
      String key = participantKey(participant);
      if (key == null) {
        continue;
      }
      byKey.merge(key, entry.getAmount(), BigDecimal::add);
    }
    for (RefineryOrder order : orders) {
      if (order.getOwner() == null) {
        continue;
      }
      double costs = order.getExpenses() != null ? order.getExpenses() : 0d;
      double otherCosts = order.getOtherExpenses() != null ? order.getOtherExpenses() : 0d;
      double total = costs + otherCosts;
      if (total <= 0d) {
        continue;
      }
      String key = order.getOwner().getId().toString();
      byKey.merge(key, BigDecimal.valueOf(total), BigDecimal::add);
    }
    return byKey;
  }

  /**
   * Builds the duration / preference / display-name maps in one pass over the operation's
   * mission-participant graph. Missions without both actualStart and actualEnd are skipped (they
   * have no defined attendance window). DONATE is sticky across the operation: a single DONATE
   * preference on any mission marks the participant as donating for the whole operation.
   *
   * @param operation the operation with its missions / participants pre-fetched
   * @return the participation breakdown (names, valid durations, sticky preferences, total
   *     duration)
   */
  @NotNull
  public static ParticipationBreakdown computeParticipationBreakdown(@NotNull Operation operation) {
    Map<String, String> participantNames = new HashMap<>();
    Map<String, Long> validDurations = new HashMap<>();
    Map<String, PayoutPreference> preferences = new HashMap<>();
    long totalDuration = 0L;

    for (Mission mission : operation.getMissions()) {
      Instant actualStart = mission.getActualStartTime();
      Instant actualEnd = mission.getActualEndTime();
      if (actualStart == null || actualEnd == null || !actualEnd.isAfter(actualStart)) {
        continue;
      }

      for (MissionParticipant p : mission.getParticipants()) {
        String key = participantKey(p);
        if (key == null) {
          continue;
        }

        String name = p.getUser() != null ? p.getUser().getEffectiveName() : p.getGuestName();
        participantNames.putIfAbsent(key, name);

        PayoutPreference current = preferences.getOrDefault(key, PayoutPreference.PAYOUT);
        if (p.getPayoutPreference() == PayoutPreference.DONATE) {
          preferences.put(key, PayoutPreference.DONATE);
        } else {
          preferences.putIfAbsent(key, current);
        }

        Instant participantStart = p.getStartTime();
        if (participantStart == null) {
          continue;
        }
        Instant participantEnd = p.getEndTime() != null ? p.getEndTime() : Instant.now();

        Instant effectiveStart =
            participantStart.isBefore(actualStart) ? actualStart : participantStart;
        Instant effectiveEnd = participantEnd.isAfter(actualEnd) ? actualEnd : participantEnd;

        if (effectiveEnd.isAfter(effectiveStart)) {
          long duration = Duration.between(effectiveStart, effectiveEnd).toMillis();
          validDurations.merge(key, duration, Long::sum);
          totalDuration += duration;
        }
      }
    }

    return new ParticipationBreakdown(participantNames, validDurations, preferences, totalDuration);
  }

  /**
   * Returns the opaque participant key used across the payout pipeline: the user UUID, {@code
   * "guest_<name>"} for guests, or {@code "deleted_<participantId>"} for a participant whose
   * account was hard-deleted (REQ-DATA-008), which keeps a settled operation's split stable.
   *
   * @param participant the mission participant to key
   * @return the opaque participant key, or {@code null} for an unpersisted row
   */
  @Nullable
  private static String participantKey(@NotNull MissionParticipant participant) {
    if (participant.getUser() != null) {
      return participant.getUser().getId().toString();
    }
    if (participant.getGuestName() != null) {
      return "guest_" + participant.getGuestName();
    }
    return participant.getId() != null ? "deleted_" + participant.getId() : null;
  }

  /**
   * The values produced by one pass over the operation's mission-participant graph.
   *
   * @param participantNames participant key → display name
   * @param validDurations participant key → accumulated valid attendance window, in milliseconds
   * @param preferences participant key → sticky payout preference (DONATE wins across missions)
   * @param totalDuration sum of every participant's valid attendance duration, in milliseconds
   */
  public record ParticipationBreakdown(
      Map<String, String> participantNames,
      Map<String, Long> validDurations,
      Map<String, PayoutPreference> preferences,
      long totalDuration) {}
}
