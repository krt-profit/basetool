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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationPayoutStatus;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationPayoutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationPayoutStatusDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationPayoutSummaryDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationPayoutStatusRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class OperationPayoutServiceTest {

  @Mock private OperationRepository operationRepository;
  @Mock private MissionFinanceEntryRepository financeEntryRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private OperationPayoutStatusRepository payoutStatusRepository;
  @Mock private UserService userService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuthHelperService authHelperService;
  @Mock private SystemSettingService systemSettingService;

  @Mock private AuditService auditService;

  /**
   * Self-proxy the payout toggle uses to open a fresh {@code REQUIRES_NEW} transaction per retry
   * (#1111). In these unit tests it is stubbed to return the {@link #operationPayoutService} under
   * test (or a spy of it) so the orchestrator delegates to the real / spied within-transaction
   * body.
   */
  @Mock private ObjectProvider<OperationPayoutService> self;

  @InjectMocks private OperationPayoutService operationPayoutService;

  /**
   * The payout calculator is the money-handling core of the operation flow. Its previous coverage
   * was 0% — these tests exhaustively cover the branches:
   *
   * <ol>
   *   <li>Operation lookup (not-found path).
   *   <li>Mission validity gate (null start, null end, end &lt;= start).
   *   <li>Participant identity (user vs guest vs neither).
   *   <li>Effective-window clamping (pStart &lt; actualStart, pEnd &gt; actualEnd, pEnd null falls
   *       back to now()).
   *   <li>DONATE preference precedence across multiple missions.
   *   <li>Aggregation across missions for the same participant.
   *   <li>Percentage math (total &gt; 0 vs total == 0 div-by-zero guard, two-decimal rounding).
   *   <li>Output ordering (case-insensitive by participant name).
   * </ol>
   */
  @Nested
  class GetOperationPayoutsTests {

    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T0_PLUS_60M = T0.plus(60, ChronoUnit.MINUTES);
    private static final Instant T0_PLUS_30M = T0.plus(30, ChronoUnit.MINUTES);

    @Test
    void throwsNotFound_whenOperationDoesNotExist() {
      UUID missing = UUID.randomUUID();
      when(operationRepository.findWithMissionsAndParticipantsById(missing))
          .thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class, () -> operationPayoutService.getOperationPayouts(missing));
    }

    @Test
    void emptyOperation_returnsEmptyList() {
      stubOperation(new HashSet<>());

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertTrue(result.isEmpty());
    }

    @Test
    void missionWithNullActualStart_isSkipped() {
      Mission m = newMission(null, T0_PLUS_60M);
      addUserParticipant(m, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertTrue(result.isEmpty(), "missions without actualStart contribute nothing");
    }

    @Test
    void missionWithNullActualEnd_isSkipped() {
      Mission m = newMission(T0, null);
      addUserParticipant(m, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      assertTrue(operationPayoutService.getOperationPayouts(OPERATION_ID).isEmpty());
    }

    @Test
    void missionEndingAtSameInstantAsStart_isSkipped() {
      Mission m = newMission(T0, T0);
      addUserParticipant(m, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      assertTrue(
          operationPayoutService.getOperationPayouts(OPERATION_ID).isEmpty(),
          "actualEnd must be STRICTLY after actualStart");
    }

    @Test
    void missionEndingBeforeStart_isSkipped() {
      Mission m = newMission(T0_PLUS_60M, T0);
      addUserParticipant(m, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      assertTrue(operationPayoutService.getOperationPayouts(OPERATION_ID).isEmpty());
    }

    /**
     * An UNSAVED participant with neither user nor guest name is skipped, because participantKey()
     * has nothing to key it by. A persisted one is not -- see {@link
     * #participantOfADeletedAccount_isListedAndSortsLast()}.
     */
    @Test
    void participantWithoutUserOrGuestNameOrId_isSkipped() {
      Mission m = newMission(T0, T0_PLUS_60M);
      MissionParticipant ghost = new MissionParticipant();
      ghost.setMission(m);
      ghost.setStartTime(T0);
      ghost.setEndTime(T0_PLUS_60M);
      ghost.setPayoutPreference(PayoutPreference.PAYOUT);
      m.getParticipants().add(ghost);
      stubOperation(Set.of(m));

      assertTrue(
          operationPayoutService.getOperationPayouts(OPERATION_ID).isEmpty(),
          "participants with neither user nor guestName must not appear");
    }

    @Test
    void participantOfADeletedAccount_isListedAndSortsLast() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      MissionParticipant deleted = new MissionParticipant();
      deleted.setId(UUID.randomUUID());
      deleted.setMission(m);
      deleted.setStartTime(T0);
      deleted.setEndTime(T0_PLUS_60M);
      deleted.setPayoutPreference(PayoutPreference.PAYOUT);
      m.getParticipants().add(deleted);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(2, result.size(), "a deleted account keeps its slice; it is not dropped");
      assertEquals("alice", result.get(0).participantName());
      OperationPayoutDto ghost = result.get(1);
      assertNull(
          ghost.participantName(),
          "null is the DTO's contract for a deleted account, not something to fill in");
      assertTrue(
          ghost.participantId().startsWith("deleted_"),
          "the row keeps a stable identity so settled shares do not redistribute");
    }

    @Test
    void payoutsOfSeveralDeletedAccounts_doNotThrow() {
      Mission m = newMission(T0, T0_PLUS_60M);
      for (int i = 0; i < 2; i++) {
        MissionParticipant deleted = new MissionParticipant();
        deleted.setId(UUID.randomUUID());
        deleted.setMission(m);
        deleted.setStartTime(T0);
        deleted.setEndTime(T0_PLUS_60M);
        deleted.setPayoutPreference(PayoutPreference.PAYOUT);
        m.getParticipants().add(deleted);
      }
      stubOperation(Set.of(m));

      assertEquals(2, operationPayoutService.getOperationPayouts(OPERATION_ID).size());
    }

    @Test
    void participantWithNullStartTime_appearsInResultWithZeroPercent() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "alice", null, T0_PLUS_60M, PayoutPreference.DONATE);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size());
      assertEquals("alice", result.get(0).participantName());
      assertEquals(
          0.0,
          result.get(0).participationPercentage(),
          "null start time -> no duration accumulated -> 0%");
      assertEquals(
          PayoutPreference.DONATE,
          result.get(0).payoutPreference(),
          "preference must still be recorded even with no duration");
    }

    @Test
    void soleUserParticipant_fullDuration_gets100Percent() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size());
      assertEquals("alice", result.get(0).participantName());
      assertEquals(100.0, result.get(0).participationPercentage());
      assertEquals(PayoutPreference.PAYOUT, result.get(0).payoutPreference());
    }

    @Test
    void guestParticipant_isIncluded_byGuestName() {
      Mission m = newMission(T0, T0_PLUS_60M);
      MissionParticipant guest = new MissionParticipant();
      guest.setMission(m);
      guest.setGuestName("Bob the Guest");
      guest.setStartTime(T0);
      guest.setEndTime(T0_PLUS_60M);
      guest.setPayoutPreference(PayoutPreference.PAYOUT);
      m.getParticipants().add(guest);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size());
      OperationPayoutDto row = result.get(0);
      assertEquals("Bob the Guest", row.participantName());
      assertTrue(
          row.participantId().startsWith("guest_"),
          "guest IDs must be prefixed to avoid colliding with user UUIDs");
      assertEquals(100.0, row.participationPercentage());
    }

    @Test
    void participantStartBeforeMissionStart_isClampedToMissionStart() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(
          m, "alice", T0.minus(60, ChronoUnit.MINUTES), T0_PLUS_30M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size());
      assertEquals(
          100.0,
          result.get(0).participationPercentage(),
          "alice is the only contributor so her share is 100% even when clamped");
    }

    @Test
    void participantEndAfterMissionEnd_isClampedToMissionEnd() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(
          m,
          "alice",
          T0_PLUS_30M,
          T0_PLUS_60M.plus(60, ChronoUnit.MINUTES),
          PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size());
      assertEquals(100.0, result.get(0).participationPercentage());
    }

    @Test
    void participantWithNullEndTime_clampedToInstantNow() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "alice", T0_PLUS_30M, null, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size());
      assertEquals(100.0, result.get(0).participationPercentage());
    }

    @Test
    void twoEquallyParticipatingUsers_splitFiftyFifty() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      addUserParticipant(m, "bob", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(2, result.size());
      assertEquals(50.0, result.get(0).participationPercentage());
      assertEquals(50.0, result.get(1).participationPercentage());
      assertEquals(
          100.0,
          result.get(0).participationPercentage() + result.get(1).participationPercentage(),
          "shares must sum to 100% (no rounding losses for a 50/50 split)");
    }

    @Test
    void unequalDurations_produceProportionalPercentages() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      addUserParticipant(m, "bob", T0, T0_PLUS_30M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(2, result.size());
      OperationPayoutDto alice =
          result.stream()
              .filter(r -> r.participantName().equals("alice"))
              .findFirst()
              .orElseThrow();
      OperationPayoutDto bob =
          result.stream().filter(r -> r.participantName().equals("bob")).findFirst().orElseThrow();
      assertEquals(
          66.67,
          alice.participationPercentage(),
          "60 / 90 = 66.666..., rounded to 2 decimals = 66.67");
      assertEquals(33.33, bob.participationPercentage());
    }

    @Test
    void donatePreferenceOnAnyMission_overridesPayoutFromOtherMissions() {
      Mission m1 = newMission(T0, T0_PLUS_60M);
      Mission m2 = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      addUserParticipantWithUser(m1, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      addUserParticipantWithUser(m2, alice, T0, T0_PLUS_60M, PayoutPreference.DONATE);
      stubOperation(Set.of(m1, m2));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size());
      assertEquals(
          PayoutPreference.DONATE,
          result.get(0).payoutPreference(),
          "any DONATE preference must win across missions");
    }

    @Test
    void payoutPreference_doesNotOverrideEarlierDonate() {
      Mission m1 = newMission(T0, T0_PLUS_60M);
      Mission m2 = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      addUserParticipantWithUser(m1, alice, T0, T0_PLUS_60M, PayoutPreference.DONATE);
      addUserParticipantWithUser(m2, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m1, m2));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(PayoutPreference.DONATE, result.get(0).payoutPreference());
    }

    @Test
    void durationsAcrossMultipleMissions_accumulateForSameUser() {
      Mission m1 = newMission(T0, T0_PLUS_30M);
      Mission m2 = newMission(T0_PLUS_60M, T0_PLUS_60M.plus(30, ChronoUnit.MINUTES));
      User alice = newUser("alice");
      addUserParticipantWithUser(m1, alice, T0, T0_PLUS_30M, PayoutPreference.PAYOUT);
      addUserParticipantWithUser(
          m2,
          alice,
          T0_PLUS_60M,
          T0_PLUS_60M.plus(30, ChronoUnit.MINUTES),
          PayoutPreference.PAYOUT);
      stubOperation(Set.of(m1, m2));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size(), "same user across two missions still produces one row");
      assertEquals(100.0, result.get(0).participationPercentage());
    }

    @Test
    void resultIsSortedCaseInsensitivelyByParticipantName() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "charlie", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      addUserParticipant(m, "Alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      addUserParticipant(m, "bob", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(
          List.of("Alice", "bob", "charlie"),
          result.stream().map(OperationPayoutDto::participantName).toList());
    }

    @Test
    void participantWithEndAtSameInstantAsEffectiveStart_isSkipped() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "alice", T0_PLUS_30M, T0_PLUS_30M, PayoutPreference.PAYOUT);
      addUserParticipant(m, "bob", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(2, result.size());
      OperationPayoutDto alice =
          result.stream()
              .filter(r -> r.participantName().equals("alice"))
              .findFirst()
              .orElseThrow();
      OperationPayoutDto bob =
          result.stream().filter(r -> r.participantName().equals("bob")).findFirst().orElseThrow();
      assertEquals(0.0, alice.participationPercentage());
      assertEquals(100.0, bob.participationPercentage());
    }

    @Test
    void allParticipantsHaveZeroValidDuration_dividesByZeroSafely() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "alice", T0_PLUS_30M, T0_PLUS_30M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size());
      assertEquals(
          0.0,
          result.get(0).participationPercentage(),
          "div-by-zero must clamp to 0.0, not produce NaN");
    }

    @Test
    void userDisplayName_isPreferredOverUsername() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User u = newUser("alice");
      u.setDisplayName("Alice Liddell");
      addUserParticipantWithUser(m, u, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals("Alice Liddell", result.get(0).participantName());
    }

    private void stubOperation(Set<Mission> missions) {
      Operation op = new Operation();
      op.setId(OPERATION_ID);
      op.setMissions(missions);
      when(operationRepository.findWithMissionsAndParticipantsById(OPERATION_ID))
          .thenReturn(Optional.of(op));
    }

    private Mission newMission(Instant actualStart, Instant actualEnd) {
      Mission m = new Mission();
      m.setId(UUID.randomUUID());
      m.setActualStartTime(actualStart);
      m.setActualEndTime(actualEnd);
      return m;
    }

    private User newUser(String username) {
      User u = new User();
      u.setId(UUID.randomUUID());
      u.setUsername(username);
      return u;
    }

    private void addUserParticipant(
        Mission mission, String username, Instant start, Instant end, PayoutPreference pref) {
      addUserParticipantWithUser(mission, newUser(username), start, end, pref);
    }

    private void addUserParticipantWithUser(
        Mission mission, User user, Instant start, Instant end, PayoutPreference pref) {
      MissionParticipant p = new MissionParticipant();
      p.setMission(mission);
      p.setUser(user);
      p.setStartTime(start);
      p.setEndTime(end);
      p.setPayoutPreference(pref);
      mission.getParticipants().add(p);
    }

    @SuppressWarnings("unused")
    private static long minutes(int n) {
      return Duration.ofMinutes(n).toMillis();
    }
  }

  /**
   * Coverage for the money-side of the payout breakdown. The reimbursement-first model says: each
   * participant's out-of-pocket expenses (mission EXPENSE entries owned by them + refinery orders'
   * costs they own) are paid back from gross income, and the remaining {@code totalSum} is split
   * per participation percentage among PAYOUT participants. DONATE participants keep their
   * reimbursement (it is their own money returned) but contribute their share. Finally an in-game
   * banking fee is deducted from every participant's gross payout, and the resulting net is rounded
   * HALF_UP to whole aUEC, so {@code payoutAmount = round(personalExpenses + shareAmount -
   * transferFee)}. The fee rate comes from the runtime-editable {@code operation.transfer_fee_rate}
   * system setting and falls back to 0.5% when the row is missing — tests that don't stub {@code
   * systemSettingService.getSettingValue(...)} therefore exercise the 0.5% fallback path, which is
   * what the existing assertions are calibrated to. The combined paid-out fields are covered
   * together because they share the same setup.
   */
  @Nested
  class GetOperationPayoutsAmountTests {

    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T0_PLUS_60M = T0.plus(60, ChronoUnit.MINUTES);

    @Test
    void incomeOnly_splitsEquallyBetweenTwoPayoutParticipants() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      User bob = newUser("bob");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      addUserParticipantWithUser(m, bob, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income =
          newEntry(m, aliceP, FinanceType.INCOME, new BigDecimal("1000.00"));
      stubFinances(List.of(income), List.of());

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      OperationPayoutDto aliceRow = byName(result, "alice");
      OperationPayoutDto bobRow = byName(result, "bob");
      assertEquals(new BigDecimal("0.00"), aliceRow.personalExpenses());
      assertEquals(new BigDecimal("500.00"), aliceRow.shareAmount());
      assertEquals(new BigDecimal("2.50"), aliceRow.transferFee());
      assertEquals(new BigDecimal("498"), aliceRow.payoutAmount());
      assertEquals(new BigDecimal("0.00"), bobRow.personalExpenses());
      assertEquals(new BigDecimal("500.00"), bobRow.shareAmount());
      assertEquals(new BigDecimal("2.50"), bobRow.transferFee());
      assertEquals(new BigDecimal("498"), bobRow.payoutAmount());
    }

    @Test
    void missionExpenseAttributedToParticipant_reimbursedOffTheTop_thenRemainderSplit() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      User bob = newUser("bob");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      MissionParticipant bobP =
          addUserParticipantWithUser(m, bob, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income = newEntry(m, bobP, FinanceType.INCOME, new BigDecimal("1000.00"));
      MissionFinanceEntry expense =
          newEntry(m, aliceP, FinanceType.EXPENSE, new BigDecimal("300.00"));
      stubFinances(List.of(income, expense), List.of());

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      OperationPayoutDto aliceRow = byName(result, "alice");
      OperationPayoutDto bobRow = byName(result, "bob");
      assertEquals(new BigDecimal("300.00"), aliceRow.personalExpenses());
      assertEquals(new BigDecimal("350.00"), aliceRow.shareAmount());
      assertEquals(new BigDecimal("3.25"), aliceRow.transferFee(), "0.5% of 650.00 gross");
      assertEquals(
          new BigDecimal("647"),
          aliceRow.payoutAmount(),
          "alice's payout = round(reimbursement (300) + share (350) - fee (3.25)) HALF_UP -> 647");
      assertEquals(new BigDecimal("0.00"), bobRow.personalExpenses());
      assertEquals(new BigDecimal("350.00"), bobRow.shareAmount());
      assertEquals(new BigDecimal("1.75"), bobRow.transferFee(), "0.5% of 350.00 gross");
      assertEquals(new BigDecimal("348"), bobRow.payoutAmount());
    }

    @Test
    void refineryOrderCosts_attributedToOwner_asReimbursement() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      User bob = newUser("bob");
      addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      addUserParticipantWithUser(m, bob, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      RefineryOrder order = new RefineryOrder();
      order.setId(UUID.randomUUID());
      order.setOwner(alice);
      order.setMission(m);
      order.setOreSales(2000d);
      order.setExpenses(500d);
      order.setOtherExpenses(200d);
      stubFinances(List.of(), List.of(order));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      OperationPayoutDto aliceRow = byName(result, "alice");
      OperationPayoutDto bobRow = byName(result, "bob");
      assertEquals(new BigDecimal("700.00"), aliceRow.personalExpenses());
      assertEquals(new BigDecimal("650.00"), aliceRow.shareAmount());
      assertEquals(new BigDecimal("6.75"), aliceRow.transferFee());
      assertEquals(new BigDecimal("1343"), aliceRow.payoutAmount());
      assertEquals(new BigDecimal("0.00"), bobRow.personalExpenses());
      assertEquals(new BigDecimal("650.00"), bobRow.shareAmount());
      assertEquals(new BigDecimal("3.25"), bobRow.transferFee());
      assertEquals(new BigDecimal("647"), bobRow.payoutAmount());
    }

    @Test
    void donateParticipantKeepsReimbursementButGetsZeroShare() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      User bob = newUser("bob");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.DONATE);
      addUserParticipantWithUser(m, bob, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income =
          newEntry(m, aliceP, FinanceType.INCOME, new BigDecimal("1000.00"));
      MissionFinanceEntry expense =
          newEntry(m, aliceP, FinanceType.EXPENSE, new BigDecimal("300.00"));
      stubFinances(List.of(income, expense), List.of());

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      OperationPayoutDto aliceRow = byName(result, "alice");
      OperationPayoutDto bobRow = byName(result, "bob");
      assertEquals(PayoutPreference.DONATE, aliceRow.payoutPreference());
      assertEquals(new BigDecimal("300.00"), aliceRow.personalExpenses());
      assertEquals(
          new BigDecimal("0.00"),
          aliceRow.shareAmount(),
          "DONATE participants contribute their share; only reimbursement is paid out");
      assertEquals(
          new BigDecimal("350.00"),
          aliceRow.donatedAmount(),
          "the share alice forgoes (50% of the 700 pool) is surfaced as her donatedAmount");
      assertEquals(new BigDecimal("1.50"), aliceRow.transferFee());
      assertEquals(new BigDecimal("299"), aliceRow.payoutAmount());
      assertEquals(new BigDecimal("1.75"), bobRow.transferFee());
      assertEquals(new BigDecimal("348"), bobRow.payoutAmount());
      assertEquals(
          new BigDecimal("350.00"),
          bobRow.shareAmount(),
          "PAYOUT share stays the donor-inclusive percentage of the full pool");
      assertEquals(
          new BigDecimal("0.00"),
          bobRow.donatedAmount(),
          "PAYOUT participants contribute nothing to donations");
    }

    @Test
    void guestParticipantExpenses_areReimbursedToGuestKey() {
      Mission m = newMission(T0, T0_PLUS_60M);
      MissionParticipant guest = new MissionParticipant();
      guest.setMission(m);
      guest.setGuestName("Gary");
      guest.setStartTime(T0);
      guest.setEndTime(T0_PLUS_60M);
      guest.setPayoutPreference(PayoutPreference.PAYOUT);
      m.getParticipants().add(guest);
      stubOperation(Set.of(m));

      MissionFinanceEntry expense =
          newEntry(m, guest, FinanceType.EXPENSE, new BigDecimal("250.00"));
      MissionFinanceEntry income = newEntry(m, guest, FinanceType.INCOME, new BigDecimal("500.00"));
      stubFinances(List.of(income, expense), List.of());

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      assertEquals(1, result.size());
      OperationPayoutDto row = result.get(0);
      assertTrue(row.participantId().startsWith("guest_"));
      assertEquals(new BigDecimal("250.00"), row.personalExpenses());
      assertEquals(new BigDecimal("250.00"), row.shareAmount());
      assertEquals(new BigDecimal("2.50"), row.transferFee());
      assertEquals(new BigDecimal("498"), row.payoutAmount());
    }

    @Test
    void refineryOrderWithNullCosts_isTreatedAsZeroExpense() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      RefineryOrder order = new RefineryOrder();
      order.setId(UUID.randomUUID());
      order.setOwner(alice);
      order.setMission(m);
      order.setOreSales(1000d);
      order.setExpenses(null);
      order.setOtherExpenses(null);
      stubFinances(List.of(), List.of(order));

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      OperationPayoutDto aliceRow = byName(result, "alice");
      assertEquals(new BigDecimal("0.00"), aliceRow.personalExpenses());
      assertEquals(new BigDecimal("1000.00"), aliceRow.shareAmount());
      assertEquals(new BigDecimal("5.00"), aliceRow.transferFee());
      assertEquals(new BigDecimal("995"), aliceRow.payoutAmount());
    }

    @Test
    void transferFee_isZero_whenGrossPayoutIsZero() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      User bob = newUser("bob");
      addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.DONATE);
      MissionParticipant bobP =
          addUserParticipantWithUser(m, bob, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income = newEntry(m, bobP, FinanceType.INCOME, new BigDecimal("1000.00"));
      stubFinances(List.of(income), List.of());

      List<OperationPayoutDto> result = operationPayoutService.getOperationPayouts(OPERATION_ID);

      OperationPayoutDto aliceRow = byName(result, "alice");
      assertEquals(new BigDecimal("0.00"), aliceRow.personalExpenses());
      assertEquals(new BigDecimal("0.00"), aliceRow.shareAmount());
      assertEquals(
          new BigDecimal("0.00"),
          aliceRow.transferFee(),
          "no gross payout means no in-game transfer happens; fee must be zero");
      assertEquals(new BigDecimal("0"), aliceRow.payoutAmount());
    }

    @Test
    void transferFee_roundsHalfUp_onUnevenGross() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income =
          newEntry(m, aliceP, FinanceType.INCOME, new BigDecimal("333.33"));
      stubFinances(List.of(income), List.of());

      OperationPayoutDto row = operationPayoutService.getOperationPayouts(OPERATION_ID).get(0);

      assertEquals(new BigDecimal("333.33"), row.shareAmount());
      assertEquals(
          new BigDecimal("1.67"), row.transferFee(), "333.33 * 0.005 = 1.66665 rounds to 1.67");
      assertEquals(new BigDecimal("332"), row.payoutAmount());
    }

    @Test
    void transferFee_usesRateFromSystemSetting_whenPresent() {
      when(systemSettingService.getSettingValue("operation.transfer_fee_rate"))
          .thenReturn(Optional.of("0.01"));
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income =
          newEntry(m, aliceP, FinanceType.INCOME, new BigDecimal("1000.00"));
      stubFinances(List.of(income), List.of());

      OperationPayoutDto row = operationPayoutService.getOperationPayouts(OPERATION_ID).get(0);

      assertEquals(new BigDecimal("10.00"), row.transferFee());
      assertEquals(new BigDecimal("990"), row.payoutAmount());
    }

    @Test
    void transferFee_fallsBackToDefault_whenSettingIsBlank() {
      when(systemSettingService.getSettingValue("operation.transfer_fee_rate"))
          .thenReturn(Optional.of("   "));
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income =
          newEntry(m, aliceP, FinanceType.INCOME, new BigDecimal("1000.00"));
      stubFinances(List.of(income), List.of());

      OperationPayoutDto row = operationPayoutService.getOperationPayouts(OPERATION_ID).get(0);

      assertEquals(new BigDecimal("5.00"), row.transferFee());
      assertEquals(new BigDecimal("995"), row.payoutAmount());
    }

    @Test
    void transferFee_fallsBackToDefault_whenSettingIsUnparseable() {
      when(systemSettingService.getSettingValue("operation.transfer_fee_rate"))
          .thenReturn(Optional.of("five percent"));
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income =
          newEntry(m, aliceP, FinanceType.INCOME, new BigDecimal("1000.00"));
      stubFinances(List.of(income), List.of());

      OperationPayoutDto row = operationPayoutService.getOperationPayouts(OPERATION_ID).get(0);

      assertEquals(new BigDecimal("5.00"), row.transferFee());
      assertEquals(new BigDecimal("995"), row.payoutAmount());
    }

    @Test
    void transferFee_fallsBackToDefault_whenSettingIsOutOfRange() {
      when(systemSettingService.getSettingValue("operation.transfer_fee_rate"))
          .thenReturn(Optional.of("1.5"));
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income =
          newEntry(m, aliceP, FinanceType.INCOME, new BigDecimal("1000.00"));
      stubFinances(List.of(income), List.of());

      OperationPayoutDto row = operationPayoutService.getOperationPayouts(OPERATION_ID).get(0);

      assertEquals(new BigDecimal("5.00"), row.transferFee());
      assertEquals(new BigDecimal("995"), row.payoutAmount());
    }

    @Test
    void transferFee_acceptsZeroRate_disablingTheFee() {
      when(systemSettingService.getSettingValue("operation.transfer_fee_rate"))
          .thenReturn(Optional.of("0"));
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      MissionFinanceEntry income =
          newEntry(m, aliceP, FinanceType.INCOME, new BigDecimal("1000.00"));
      stubFinances(List.of(income), List.of());

      OperationPayoutDto row = operationPayoutService.getOperationPayouts(OPERATION_ID).get(0);

      assertEquals(new BigDecimal("0.00"), row.transferFee());
      assertEquals(new BigDecimal("1000"), row.payoutAmount());
    }

    @Test
    void paidOutFlag_isFalseWhenNoStatusRowExists() {
      Mission m = newMission(T0, T0_PLUS_60M);
      addUserParticipant(m, "alice", T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      OperationPayoutDto row = operationPayoutService.getOperationPayouts(OPERATION_ID).get(0);

      assertFalse(row.paidOut(), "absent status row means not paid out");
      assertNull(row.paidOutAt());
      assertNull(row.paidOutByName());
    }

    @Test
    void paidOutFlag_reflectsExistingStatusRow() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));

      User auditor = newUser("officer");
      auditor.setDisplayName("Officer Bob");
      Instant when = Instant.parse("2026-03-02T15:00:00Z");
      OperationPayoutStatus status = new OperationPayoutStatus();
      status.setOperation(null);
      status.setParticipantKey(alice.getId().toString());
      status.setPaidOut(true);
      status.setPaidOutAt(when);
      status.setPaidOutByUser(auditor);
      when(payoutStatusRepository.findByOperationId(OPERATION_ID)).thenReturn(List.of(status));

      OperationPayoutDto row = operationPayoutService.getOperationPayouts(OPERATION_ID).get(0);

      assertTrue(row.paidOut());
      assertEquals(when, row.paidOutAt());
      assertEquals("Officer Bob", row.paidOutByName());
    }

    @Test
    void summary_totalDonations_aggregatesEveryDonorsForgoneShare() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      User bob = newUser("bob");
      User carol = newUser("carol");
      addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.DONATE);
      MissionParticipant bobP =
          addUserParticipantWithUser(m, bob, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      addUserParticipantWithUser(m, carol, T0, T0_PLUS_60M, PayoutPreference.DONATE);
      stubOperation(Set.of(m));
      stubFinances(
          List.of(newEntry(m, bobP, FinanceType.INCOME, new BigDecimal("900.00"))), List.of());
      when(ownerScopeService.canSeeOperationLedger(OPERATION_ID)).thenReturn(true);

      OperationPayoutSummaryDto summary =
          operationPayoutService.getOperationPayoutSummary(OPERATION_ID);

      OperationPayoutDto aliceRow = byName(summary.payouts(), "alice");
      OperationPayoutDto carolRow = byName(summary.payouts(), "carol");
      assertEquals(
          aliceRow.donatedAmount().add(carolRow.donatedAmount()),
          summary.totalDonations(),
          "totalDonations is exactly the sum of the per-donor donated shares");
      assertEquals(
          new BigDecimal("599.94"),
          summary.totalDonations(),
          "two donors each forgoing 33.33% of the 900 pool");
    }

    @Test
    void summary_totalDonations_isZero_whenNobodyDonates() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      MissionParticipant aliceP =
          addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));
      stubFinances(
          List.of(newEntry(m, aliceP, FinanceType.INCOME, new BigDecimal("500.00"))), List.of());
      when(ownerScopeService.canSeeOperationLedger(OPERATION_ID)).thenReturn(true);

      OperationPayoutSummaryDto summary =
          operationPayoutService.getOperationPayoutSummary(OPERATION_ID);

      assertEquals(new BigDecimal("0.00"), summary.totalDonations());
    }

    /**
     * Audit MEDIUM-1: a caller who reached the operation only through the participant escape gets
     * their own row and nothing else.
     *
     * <p>The escape is self-issuable - {@code POST /api/v1/missions/&#123;id&#125;/join} is open
     * for every non-internal mission of every org unit - so honouring it with the full breakdown
     * meant one request bought a foreign unit's entire payout table, callsigns and amounts
     * included.
     */
    @Test
    void summary_escapeOnlyCaller_seesOnlyTheirOwnRow() {
      Mission m = newMission(T0, T0_PLUS_60M);
      User alice = newUser("alice");
      User bob = newUser("bob");
      addUserParticipantWithUser(m, alice, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      MissionParticipant bobP =
          addUserParticipantWithUser(m, bob, T0, T0_PLUS_60M, PayoutPreference.PAYOUT);
      stubOperation(Set.of(m));
      stubFinances(
          List.of(newEntry(m, bobP, FinanceType.INCOME, new BigDecimal("900.00"))), List.of());
      when(ownerScopeService.canSeeOperationLedger(OPERATION_ID)).thenReturn(false);
      when(authHelperService.currentUserId()).thenReturn(java.util.Optional.of(bob.getId()));

      OperationPayoutSummaryDto summary =
          operationPayoutService.getOperationPayoutSummary(OPERATION_ID);

      assertEquals(1, summary.payouts().size(), "only the caller's own payout row");
      assertEquals(bob.getId().toString(), summary.payouts().get(0).participantId());
    }

    private void stubOperation(Set<Mission> missions) {
      Operation op = new Operation();
      op.setId(OPERATION_ID);
      op.setMissions(missions);
      when(operationRepository.findWithMissionsAndParticipantsById(OPERATION_ID))
          .thenReturn(Optional.of(op));
    }

    private void stubFinances(List<MissionFinanceEntry> entries, List<RefineryOrder> orders) {
      when(financeEntryRepository.findAllByMissionIdIn(any())).thenReturn(entries);
      when(refineryOrderRepository.findByMissionIdIn(any())).thenReturn(orders);
    }

    private Mission newMission(Instant actualStart, Instant actualEnd) {
      Mission m = new Mission();
      m.setId(UUID.randomUUID());
      m.setActualStartTime(actualStart);
      m.setActualEndTime(actualEnd);
      return m;
    }

    private User newUser(String username) {
      User u = new User();
      u.setId(UUID.randomUUID());
      u.setUsername(username);
      return u;
    }

    private MissionParticipant addUserParticipant(
        Mission mission, String username, Instant start, Instant end, PayoutPreference pref) {
      return addUserParticipantWithUser(mission, newUser(username), start, end, pref);
    }

    private MissionParticipant addUserParticipantWithUser(
        Mission mission, User user, Instant start, Instant end, PayoutPreference pref) {
      MissionParticipant p = new MissionParticipant();
      p.setMission(mission);
      p.setUser(user);
      p.setStartTime(start);
      p.setEndTime(end);
      p.setPayoutPreference(pref);
      mission.getParticipants().add(p);
      return p;
    }

    private MissionFinanceEntry newEntry(
        Mission mission, MissionParticipant participant, FinanceType type, BigDecimal amount) {
      return MissionFinanceEntry.builder()
          .id(UUID.randomUUID())
          .mission(mission)
          .participant(participant)
          .type(type)
          .amount(amount)
          .build();
    }

    private OperationPayoutDto byName(List<OperationPayoutDto> rows, String name) {
      return rows.stream()
          .filter(r -> r.participantName().equals(name))
          .findFirst()
          .orElseThrow(() -> new AssertionError("missing row for " + name));
    }
  }

  /**
   * Tests for the mission-manager paid-out toggle. The contract: materialize a fresh status row
   * when none exists, update in place otherwise, always refresh audit fields when paid_out=true,
   * and return the freshly-rendered payout row for the updated participant.
   */
  @Nested
  class SetPayoutStatusTests {

    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant T0_PLUS_60M = T0.plus(60, ChronoUnit.MINUTES);

    @BeforeEach
    void delegateSelfToRealService() {
      when(self.getObject()).thenReturn(operationPayoutService);
    }

    @Test
    void throwsNotFound_whenOperationDoesNotExist() {
      when(operationRepository.findWithMissionsAndParticipantsById(OPERATION_ID))
          .thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> operationPayoutService.setPayoutStatus(OPERATION_ID, "anything", true));
    }

    @Test
    void createsNewRow_whenStatusDoesNotExistYet_andRecordsAuditFields() {
      User alice = newUser("alice");
      String key = alice.getId().toString();

      when(payoutStatusRepository.findByOperationIdAndParticipantKey(OPERATION_ID, key))
          .thenReturn(Optional.empty());

      Operation op = stubOperationWithParticipant(alice);

      User actor = newUser("officer");
      actor.setDisplayName("Officer Bob");
      when(userService.getCurrentUser()).thenReturn(Optional.of(actor));

      operationPayoutService.setPayoutStatus(OPERATION_ID, key, true);

      ArgumentCaptor<OperationPayoutStatus> captor =
          ArgumentCaptor.forClass(OperationPayoutStatus.class);
      verify(payoutStatusRepository).save(captor.capture());
      OperationPayoutStatus saved = captor.getValue();
      assertEquals(key, saved.getParticipantKey());
      assertTrue(saved.isPaidOut());
      assertNotNull(saved.getPaidOutAt(), "paid_out_at must be stamped on transition to true");
      assertEquals(actor, saved.getPaidOutByUser());
      assertEquals(op, saved.getOperation());
    }

    @Test
    void updatesExistingRow_inPlace() {
      User alice = newUser("alice");
      String key = alice.getId().toString();

      OperationPayoutStatus existing = new OperationPayoutStatus();
      existing.setId(UUID.randomUUID());
      existing.setParticipantKey(key);
      existing.setPaidOut(false);

      when(payoutStatusRepository.findByOperationIdAndParticipantKey(OPERATION_ID, key))
          .thenReturn(Optional.of(existing));

      stubOperationWithParticipant(alice);

      User actor = newUser("officer");
      when(userService.getCurrentUser()).thenReturn(Optional.of(actor));

      operationPayoutService.setPayoutStatus(OPERATION_ID, key, true);

      ArgumentCaptor<OperationPayoutStatus> captor =
          ArgumentCaptor.forClass(OperationPayoutStatus.class);
      verify(payoutStatusRepository).save(captor.capture());
      OperationPayoutStatus saved = captor.getValue();
      assertEquals(existing, saved, "must update the same instance, not insert a duplicate row");
      assertTrue(saved.isPaidOut());
      assertNotNull(saved.getPaidOutAt());
      assertEquals(actor, saved.getPaidOutByUser());
    }

    @Test
    void togglingPaidOutToFalse_keepsAuditTrailFromPriorTrue() {
      User alice = newUser("alice");
      String key = alice.getId().toString();
      Instant previouslyPaidAt = Instant.parse("2026-03-01T08:00:00Z");
      User previouslyAuditedBy = newUser("formerOfficer");
      OperationPayoutStatus existing = new OperationPayoutStatus();
      existing.setId(UUID.randomUUID());
      existing.setParticipantKey(key);
      existing.setPaidOut(true);
      existing.setPaidOutAt(previouslyPaidAt);
      existing.setPaidOutByUser(previouslyAuditedBy);

      when(payoutStatusRepository.findByOperationIdAndParticipantKey(OPERATION_ID, key))
          .thenReturn(Optional.of(existing));
      stubOperationWithParticipant(alice);

      operationPayoutService.setPayoutStatus(OPERATION_ID, key, false);

      ArgumentCaptor<OperationPayoutStatus> captor =
          ArgumentCaptor.forClass(OperationPayoutStatus.class);
      verify(payoutStatusRepository).save(captor.capture());
      OperationPayoutStatus saved = captor.getValue();
      assertFalse(saved.isPaidOut());
      assertEquals(
          previouslyPaidAt,
          saved.getPaidOutAt(),
          "paid_out_at must survive a toggle back to false as a historical audit trace");
      assertEquals(
          previouslyAuditedBy,
          saved.getPaidOutByUser(),
          "paid_out_by_user must survive a toggle back to false");
    }

    @Test
    void throwsNotFound_whenParticipantKeyIsUnknownInTheOperation() {
      String unknownKey = "guest_someone-who-was-never-in-this-op";

      stubOperationWithParticipant(newUser("alice"));

      assertThrows(
          NotFoundException.class,
          () -> operationPayoutService.setPayoutStatus(OPERATION_ID, unknownKey, true));
    }

    @Test
    void recordsPayoutToggledAuditEvent_withPaidOutDetail_andNoParticipantName() {
      User alice = newUser("alice");
      String key = alice.getId().toString();

      when(payoutStatusRepository.findByOperationIdAndParticipantKey(OPERATION_ID, key))
          .thenReturn(Optional.empty());
      stubOperationWithParticipant(alice);

      User actor = newUser("officer");
      actor.setDisplayName("Officer Bob");
      when(userService.getCurrentUser()).thenReturn(Optional.of(actor));

      operationPayoutService.setPayoutStatus(OPERATION_ID, key, true);

      ArgumentCaptor<CharSequence> detailsCaptor = ArgumentCaptor.forClass(CharSequence.class);
      verify(auditService)
          .record(
              eq(AuditEventType.OPERATION_PAYOUT_TOGGLED),
              eq(OPERATION_ID),
              any(),
              isNull(),
              detailsCaptor.capture());
      String details = detailsCaptor.getValue().toString();
      assertEquals("paidOut=true", details, "details must carry exactly the paidOut flag");
      assertFalse(
          details.contains("alice"),
          "audit details must not leak the participant name (REQ-AUDIT-001)");
    }

    private Operation stubOperationWithParticipant(User user) {
      Mission m = new Mission();
      m.setId(UUID.randomUUID());
      m.setActualStartTime(T0);
      m.setActualEndTime(T0_PLUS_60M);

      MissionParticipant p = new MissionParticipant();
      p.setMission(m);
      p.setUser(user);
      p.setStartTime(T0);
      p.setEndTime(T0_PLUS_60M);
      p.setPayoutPreference(PayoutPreference.PAYOUT);
      m.getParticipants().add(p);

      Operation op = new Operation();
      op.setId(OPERATION_ID);
      Set<Mission> missions = new HashSet<>();
      missions.add(m);
      op.setMissions(missions);

      when(operationRepository.findWithMissionsAndParticipantsById(OPERATION_ID))
          .thenReturn(Optional.of(op));
      return op;
    }

    private User newUser(String username) {
      User u = new User();
      u.setId(UUID.randomUUID());
      u.setUsername(username);
      return u;
    }
  }

  /**
   * Tests the concurrency contract of the payout toggle (#1111): two leads ticking the same
   * participant race on the unique constraint / {@code @Version}, and the loser must retry in a
   * fresh transaction rather than 409 — so last-writer-wins actually holds. Drives the orchestrator
   * ({@link OperationPayoutService#setPayoutStatus}) with a spied within-transaction body to
   * simulate the race deterministically.
   */
  @Nested
  class SetPayoutStatusConcurrencyTests {

    private static final UUID OPERATION_ID = UUID.randomUUID();
    private static final String KEY = "participant-key";

    @Test
    void retriesInAFreshTransaction_whenTheInsertRaceLoses() {
      OperationPayoutService spied = spy(operationPayoutService);
      when(self.getObject()).thenReturn(spied);
      OperationPayoutStatusDto expected = sampleStatusDto();
      doThrow(
              new DataIntegrityViolationException(
                  "uk_operation_payout_status_operation_participant"))
          .doReturn(expected)
          .when(spied)
          .setPayoutStatusWithinTransaction(OPERATION_ID, KEY, true);

      OperationPayoutStatusDto result =
          operationPayoutService.setPayoutStatus(OPERATION_ID, KEY, true);

      assertEquals(expected, result, "the winning retry's row must be returned");
      verify(spied, times(2)).setPayoutStatusWithinTransaction(OPERATION_ID, KEY, true);
    }

    @Test
    void retriesInAFreshTransaction_whenTheUpdateRaceLoses() {
      OperationPayoutService spied = spy(operationPayoutService);
      when(self.getObject()).thenReturn(spied);
      OperationPayoutStatusDto expected = sampleStatusDto();
      doThrow(new ObjectOptimisticLockingFailureException(OperationPayoutStatus.class, null))
          .doReturn(expected)
          .when(spied)
          .setPayoutStatusWithinTransaction(OPERATION_ID, KEY, false);

      OperationPayoutStatusDto result =
          operationPayoutService.setPayoutStatus(OPERATION_ID, KEY, false);

      assertEquals(expected, result);
      verify(spied, times(2)).setPayoutStatusWithinTransaction(OPERATION_ID, KEY, false);
    }

    @Test
    void propagatesConflict_whenEveryAttemptLosesTheRace() {
      OperationPayoutService spied = spy(operationPayoutService);
      when(self.getObject()).thenReturn(spied);
      doThrow(new ObjectOptimisticLockingFailureException(OperationPayoutStatus.class, null))
          .when(spied)
          .setPayoutStatusWithinTransaction(OPERATION_ID, KEY, true);

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> operationPayoutService.setPayoutStatus(OPERATION_ID, KEY, true));
      verify(spied, times(3)).setPayoutStatusWithinTransaction(OPERATION_ID, KEY, true);
    }

    @Test
    void winsOnTheFinalAttempt_returnsTheCommittedRow() {
      OperationPayoutService spied = spy(operationPayoutService);
      when(self.getObject()).thenReturn(spied);
      OperationPayoutStatusDto expected = sampleStatusDto();
      doThrow(new ObjectOptimisticLockingFailureException(OperationPayoutStatus.class, null))
          .doThrow(
              new DataIntegrityViolationException(
                  "uk_operation_payout_status_operation_participant"))
          .doReturn(expected)
          .when(spied)
          .setPayoutStatusWithinTransaction(OPERATION_ID, KEY, true);

      OperationPayoutStatusDto result =
          operationPayoutService.setPayoutStatus(OPERATION_ID, KEY, true);

      assertEquals(
          expected, result, "the final out-of-loop attempt's returned row must be honoured");
      verify(spied, times(3)).setPayoutStatusWithinTransaction(OPERATION_ID, KEY, true);
    }

    @Test
    void deterministicNotFound_isAttemptedExactlyOnce_neverRetried() {
      OperationPayoutService spied = spy(operationPayoutService);
      when(self.getObject()).thenReturn(spied);
      when(operationRepository.findWithMissionsAndParticipantsById(OPERATION_ID))
          .thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> operationPayoutService.setPayoutStatus(OPERATION_ID, KEY, true));
      verify(spied, times(1)).setPayoutStatusWithinTransaction(OPERATION_ID, KEY, true);
    }

    private OperationPayoutStatusDto sampleStatusDto() {
      return new OperationPayoutStatusDto(KEY, true, null, null);
    }
  }
}
