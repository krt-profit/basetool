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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.service.OperationPayoutCalculator.ParticipationBreakdown;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Directly exercises the pure static math extracted into {@link OperationPayoutCalculator}: the
 * operation total sum, the per-owner out-of-pocket reimbursement map and the per-participant
 * attendance breakdown. These branches are also covered end-to-end through {@code
 * OperationPayoutServiceTest}, but pinning them at the calculator boundary keeps the arithmetic
 * regression-guarded without the payout service's repository / audit collaborators.
 */
class OperationPayoutCalculatorTest {

  private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
  private static final Instant T0_PLUS_60M = T0.plus(60, ChronoUnit.MINUTES);
  private static final Instant T0_PLUS_30M = T0.plus(30, ChronoUnit.MINUTES);

  @Nested
  class ComputeTotalSum {

    @Test
    void emptyInputs_sumToZero() {
      assertEquals(
          0,
          OperationPayoutCalculator.computeTotalSum(List.of(), List.of())
              .compareTo(BigDecimal.ZERO));
    }

    @Test
    void incomeMinusExpensePlusRefineryProfit() {
      MissionFinanceEntry income = financeEntry(FinanceType.INCOME, new BigDecimal("1000"));
      MissionFinanceEntry expense = financeEntry(FinanceType.EXPENSE, new BigDecimal("250"));
      RefineryOrder order = refineryOrder(500d, 100d, 50d); // profit = 350

      BigDecimal total =
          OperationPayoutCalculator.computeTotalSum(List.of(income, expense), List.of(order));

      // 1000 - 250 + 350 = 1100
      assertEquals(
          0, total.compareTo(new BigDecimal("1100")), "income - expense + refinery profit");
    }

    @Test
    void refineryOrderWithNullFinancials_contributesZero() {
      RefineryOrder order = refineryOrder(null, null, null);
      assertEquals(
          0,
          OperationPayoutCalculator.computeTotalSum(List.of(), List.of(order))
              .compareTo(BigDecimal.ZERO),
          "null refinery fields must be treated as 0, not NPE");
    }
  }

  @Nested
  class ComputePersonalExpensesByParticipant {

    @Test
    void expenseEntriesAreAttributedToTheirParticipant() {
      User alice = user("alice");
      MissionParticipant p = participant(alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      MissionFinanceEntry expense = financeEntry(FinanceType.EXPENSE, new BigDecimal("42"));
      expense.setParticipant(p);

      Map<String, BigDecimal> byKey =
          OperationPayoutCalculator.computePersonalExpensesByParticipant(
              List.of(expense), List.of());

      assertEquals(1, byKey.size());
      assertEquals(0, byKey.get(alice.getId().toString()).compareTo(new BigDecimal("42")));
    }

    @Test
    void incomeEntriesAreNotAttributedToAnyParticipant() {
      User alice = user("alice");
      MissionParticipant p = participant(alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      MissionFinanceEntry income = financeEntry(FinanceType.INCOME, new BigDecimal("999"));
      income.setParticipant(p);

      assertTrue(
          OperationPayoutCalculator.computePersonalExpensesByParticipant(List.of(income), List.of())
              .isEmpty(),
          "INCOME belongs to the shared pool, never a single participant");
    }

    @Test
    void refineryCostsAreAttributedToTheOwner_salesAreNot() {
      User owner = user("owner");
      RefineryOrder order = refineryOrder(1000d, 120d, 30d); // costs 120 + 30 = 150
      order.setOwner(owner);

      Map<String, BigDecimal> byKey =
          OperationPayoutCalculator.computePersonalExpensesByParticipant(List.of(), List.of(order));

      assertEquals(
          0,
          byKey.get(owner.getId().toString()).compareTo(new BigDecimal("150")),
          "owner is reimbursed the advanced costs only; sales accrue to the pool");
    }

    @Test
    void expenseWithoutParticipant_andOrderWithoutOwner_areSkipped() {
      MissionFinanceEntry orphanExpense = financeEntry(FinanceType.EXPENSE, new BigDecimal("10"));
      RefineryOrder ownerlessOrder = refineryOrder(0d, 5d, 0d);

      assertTrue(
          OperationPayoutCalculator.computePersonalExpensesByParticipant(
                  List.of(orphanExpense), List.of(ownerlessOrder))
              .isEmpty());
    }
  }

  @Nested
  class ComputeParticipationBreakdown {

    @Test
    void missionWithoutBothActualTimes_isSkipped() {
      Mission noStart = mission(null, T0_PLUS_60M);
      addUser(noStart, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);

      ParticipationBreakdown breakdown =
          OperationPayoutCalculator.computeParticipationBreakdown(operation(noStart));

      assertTrue(breakdown.participantNames().isEmpty());
      assertEquals(0L, breakdown.totalDuration());
    }

    @Test
    void participantWithoutUserOrGuestName_isSkipped() {
      Mission m = mission(T0, T0_PLUS_60M);
      MissionParticipant ghost = participant(null, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      m.getParticipants().add(ghost);

      assertTrue(
          OperationPayoutCalculator.computeParticipationBreakdown(operation(m))
              .participantNames()
              .isEmpty());
    }

    @Test
    void guestParticipant_isKeyedByPrefixedGuestName() {
      Mission m = mission(T0, T0_PLUS_60M);
      MissionParticipant guest = participant(null, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      guest.setGuestName("Bob");
      m.getParticipants().add(guest);

      ParticipationBreakdown breakdown =
          OperationPayoutCalculator.computeParticipationBreakdown(operation(m));

      assertTrue(breakdown.participantNames().containsKey("guest_Bob"));
      assertEquals("Bob", breakdown.participantNames().get("guest_Bob"));
    }

    @Test
    void donatePreferenceOnAnyMission_isStickyAcrossTheOperation() {
      User alice = user("alice");
      Mission m1 = mission(T0, T0_PLUS_60M);
      Mission m2 = mission(T0, T0_PLUS_60M);
      addUser(m1, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      addUser(m2, alice, T0, T0_PLUS_60M, PayoutPreference.DONATE);

      ParticipationBreakdown breakdown =
          OperationPayoutCalculator.computeParticipationBreakdown(operation(m1, m2));

      assertEquals(
          PayoutPreference.DONATE,
          breakdown.preferences().get(alice.getId().toString()),
          "a single DONATE marks the participant as donating for the whole operation");
    }

    @Test
    void durationIsClampedToMissionWindow_andAccumulatedAcrossMissions() {
      User alice = user("alice");
      Mission m1 = mission(T0, T0_PLUS_30M);
      Mission m2 = mission(T0_PLUS_60M, T0_PLUS_60M.plus(30, ChronoUnit.MINUTES));
      // p starts before m1 -> clamped to T0; second stint fully inside m2.
      addUser(m1, alice, T0.minus(30, ChronoUnit.MINUTES), T0_PLUS_30M, PayoutPreference.PAYOUT);
      addUser(
          m2,
          alice,
          T0_PLUS_60M,
          T0_PLUS_60M.plus(30, ChronoUnit.MINUTES),
          PayoutPreference.PAYOUT);

      ParticipationBreakdown breakdown =
          OperationPayoutCalculator.computeParticipationBreakdown(operation(m1, m2));

      long expected =
          Instant.from(T0).until(T0_PLUS_30M, ChronoUnit.MILLIS)
              + T0_PLUS_60M.until(T0_PLUS_60M.plus(30, ChronoUnit.MINUTES), ChronoUnit.MILLIS);
      assertEquals(expected, breakdown.validDurations().get(alice.getId().toString()));
      assertEquals(expected, breakdown.totalDuration());
    }

    @Test
    void zeroLengthEffectiveWindow_recordsNameButNoDuration() {
      User alice = user("alice");
      Mission m = mission(T0, T0_PLUS_60M);
      addUser(m, alice, T0_PLUS_30M, T0_PLUS_30M, PayoutPreference.PAYOUT);

      ParticipationBreakdown breakdown =
          OperationPayoutCalculator.computeParticipationBreakdown(operation(m));

      assertTrue(
          breakdown.participantNames().containsKey(alice.getId().toString()),
          "the roster name is captured before the start-time check");
      assertFalse(breakdown.validDurations().containsKey(alice.getId().toString()));
      assertEquals(0L, breakdown.totalDuration());
    }
  }

  // ----- helpers ----------------------------------------------------

  private static User user(String username) {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername(username);
    return u;
  }

  private static Mission mission(Instant actualStart, Instant actualEnd) {
    Mission m = new Mission();
    m.setId(UUID.randomUUID());
    m.setActualStartTime(actualStart);
    m.setActualEndTime(actualEnd);
    return m;
  }

  private static Operation operation(Mission... missions) {
    Operation op = new Operation();
    op.setId(UUID.randomUUID());
    Set<Mission> set = new HashSet<>();
    for (Mission m : missions) {
      set.add(m);
    }
    op.setMissions(set);
    return op;
  }

  private static MissionParticipant participant(
      User user, Instant start, Instant end, PayoutPreference pref) {
    MissionParticipant p = new MissionParticipant();
    p.setUser(user);
    p.setStartTime(start);
    p.setEndTime(end);
    p.setPayoutPreference(pref);
    return p;
  }

  private static void addUser(
      Mission mission, String username, Instant start, Instant end, PayoutPreference pref) {
    addUser(mission, user(username), start, end, pref);
  }

  private static void addUser(
      Mission mission, User user, Instant start, Instant end, PayoutPreference pref) {
    MissionParticipant p = participant(user, start, end, pref);
    p.setMission(mission);
    mission.getParticipants().add(p);
  }

  private static MissionFinanceEntry financeEntry(FinanceType type, BigDecimal amount) {
    MissionFinanceEntry entry = new MissionFinanceEntry();
    entry.setType(type);
    entry.setAmount(amount);
    return entry;
  }

  private static RefineryOrder refineryOrder(
      Double oreSales, Double expenses, Double otherExpenses) {
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(oreSales);
    order.setExpenses(expenses);
    order.setOtherExpenses(otherExpenses);
    return order;
  }
}
