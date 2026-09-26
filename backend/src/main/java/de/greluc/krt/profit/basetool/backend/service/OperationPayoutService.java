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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
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
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the operation payout breakdown and toggles the per-participant paid-out flag.
 *
 * <p>Expenses are reimbursed first, the remaining total is split by attendance share among PAYOUT
 * participants, and the in-game transfer fee from the {@code operation.transfer_fee_rate} system
 * setting is deducted from every gross payout. The paid-out toggle is audited as {@link
 * AuditEventType#OPERATION_PAYOUT_TOGGLED}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OperationPayoutService {

  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  /**
   * Key under which the in-game banking fee rate is stored in {@code system_setting}. Seeded by
   * {@code V79__add_operation_transfer_fee_rate_setting.sql} with the documented default and
   * editable from {@code /admin/settings} by officers and admins.
   */
  static final String TRANSFER_FEE_RATE_SETTING_KEY = "operation.transfer_fee_rate";

  /**
   * Transfer-fee rate (0.5%) used when the {@code operation.transfer_fee_rate} system setting is
   * absent, blank or unparseable.
   */
  static final BigDecimal DEFAULT_TRANSFER_FEE_RATE = new BigDecimal("0.005");

  /**
   * Upper bound for the in-game banking fee rate. A value &gt;= 1 would deduct the entire payout
   * (or more), which is never a legitimate Star Citizen banking fee — reject defensively and fall
   * back to the documented default instead of zeroing out every payout silently.
   */
  private static final BigDecimal MAX_TRANSFER_FEE_RATE = BigDecimal.ONE;

  private final OperationRepository operationRepository;
  private final MissionFinanceEntryRepository financeEntryRepository;
  private final RefineryOrderRepository refineryOrderRepository;
  private final OperationPayoutStatusRepository payoutStatusRepository;
  private final UserService userService;
  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;
  private final SystemSettingService systemSettingService;
  private final AuditService auditService;

  /**
   * Proxy-backed self-reference through which {@link #setPayoutStatus} runs each attempt of {@link
   * #setPayoutStatusWithinTransaction} in its own {@code REQUIRES_NEW} transaction.
   */
  private final ObjectProvider<OperationPayoutService> self;

  /**
   * Total attempts (initial plus retries) the payout toggle makes against a concurrent writer of
   * the same row before surfacing the conflict.
   */
  private static final int MAX_PAYOUT_TOGGLE_ATTEMPTS = 3;

  /**
   * Computes the per-participant payout breakdown for the operation.
   *
   * <p>The percentage is the participant's clamped attendance time over the operation's total. The
   * payout is {@code round(personalExpenses + sharePayout − transferFee)}, rounded HALF_UP to whole
   * aUEC; the components keep two decimals. DONATE participants receive only their reimbursement.
   * Rows without an {@link OperationPayoutStatus} count as not paid out.
   *
   * @param id operation primary key
   * @return per-participant payout breakdown, sorted by participant name
   * @throws NotFoundException when no match
   */
  @NotNull
  public List<OperationPayoutDto> getOperationPayouts(@NotNull UUID id) {
    Operation operation =
        Entities.require(
            operationRepository.findWithMissionsAndParticipantsById(id), "Operation not found");

    List<UUID> missionIds = operation.getMissions().stream().map(Mission::getId).toList();
    List<MissionFinanceEntry> allEntries =
        missionIds.isEmpty() ? List.of() : financeEntryRepository.findAllByMissionIdIn(missionIds);
    List<RefineryOrder> allOrders =
        missionIds.isEmpty() ? List.of() : refineryOrderRepository.findByMissionIdIn(missionIds);

    BigDecimal totalSum = OperationPayoutCalculator.computeTotalSum(allEntries, allOrders);
    Map<String, BigDecimal> personalExpensesByKey =
        OperationPayoutCalculator.computePersonalExpensesByParticipant(allEntries, allOrders);
    BigDecimal transferFeeRate = resolveTransferFeeRate();

    OperationPayoutCalculator.ParticipationBreakdown breakdown =
        OperationPayoutCalculator.computeParticipationBreakdown(operation);

    Map<String, OperationPayoutStatus> statusByKey =
        payoutStatusRepository.findByOperationId(id).stream()
            .collect(
                Collectors.toMap(OperationPayoutStatus::getParticipantKey, Function.identity()));

    List<OperationPayoutDto> result = new ArrayList<>(breakdown.participantNames().size());
    for (Map.Entry<String, String> participant : breakdown.participantNames().entrySet()) {
      String key = participant.getKey();
      long duration = breakdown.validDurations().getOrDefault(key, 0L);
      double percentage =
          breakdown.totalDuration() > 0
              ? (double) duration / breakdown.totalDuration() * 100.0
              : 0.0;
      percentage = Math.round(percentage * 100.0) / 100.0;

      PayoutPreference pref = breakdown.preferences().getOrDefault(key, PayoutPreference.PAYOUT);
      BigDecimal personalExpenses =
          personalExpensesByKey
              .getOrDefault(key, BigDecimal.ZERO)
              .setScale(2, RoundingMode.HALF_UP);
      boolean donating = pref == PayoutPreference.DONATE;
      BigDecimal fullShare =
          totalSum
              .multiply(BigDecimal.valueOf(percentage))
              .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
      BigDecimal zeroShare = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
      BigDecimal shareAmount = donating ? zeroShare : fullShare;
      BigDecimal donatedAmount = donating ? fullShare : zeroShare;
      BigDecimal grossPayout = personalExpenses.add(shareAmount);
      BigDecimal transferFee =
          grossPayout.multiply(transferFeeRate).setScale(2, RoundingMode.HALF_UP);
      BigDecimal payoutAmount = grossPayout.subtract(transferFee).setScale(0, RoundingMode.HALF_UP);

      OperationPayoutStatus status = statusByKey.get(key);
      boolean paidOut = status != null && status.isPaidOut();
      Instant paidOutAt = status != null ? status.getPaidOutAt() : null;
      String paidOutByName =
          status != null && status.getPaidOutByUser() != null
              ? status.getPaidOutByUser().getEffectiveName()
              : null;

      result.add(
          new OperationPayoutDto(
              key,
              participant.getValue(),
              percentage,
              pref,
              personalExpenses,
              shareAmount,
              donatedAmount,
              transferFee,
              payoutAmount,
              paidOut,
              paidOutAt,
              paidOutByName));
    }

    result.sort(
        Comparator.comparing(
            OperationPayoutDto::participantName,
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
    return result;
  }

  /**
   * Returns the operation payout breakdown together with the donation total, summed from the rows'
   * {@link OperationPayoutDto#donatedAmount()}.
   *
   * @param id operation primary key
   * @return the payout rows (sorted by participant name) plus the donation total
   * @throws NotFoundException when no operation matches the id
   */
  @NotNull
  public OperationPayoutSummaryDto getOperationPayoutSummary(@NotNull UUID id) {
    List<OperationPayoutDto> payouts = restrictToOwnRowIfEscapeOnly(id, getOperationPayouts(id));
    BigDecimal totalDonations =
        payouts.stream()
            .map(OperationPayoutDto::donatedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    return new OperationPayoutSummaryDto(totalDonations, payouts);
  }

  /**
   * Reduces the breakdown to the caller's own row when they can see the operation only through the
   * participant escape (ADR-0006) rather than through its org-unit scope.
   *
   * @param operationId the operation being read
   * @param payouts the full breakdown
   * @return the full list for a scope-visible caller, otherwise only the caller's own row
   */
  private List<OperationPayoutDto> restrictToOwnRowIfEscapeOnly(
      @NotNull UUID operationId, @NotNull List<OperationPayoutDto> payouts) {
    if (ownerScopeService.canSeeOperationLedger(operationId)) {
      return payouts;
    }
    String ownKey = authHelperService.currentUserId().map(UUID::toString).orElse(null);
    if (ownKey == null) {
      return List.of();
    }
    return payouts.stream().filter(row -> ownKey.equals(row.participantId())).toList();
  }

  /**
   * Sets the paid-out flag for one participant of an operation, recording the acting user and
   * timestamp; the status row is created on the first toggle.
   *
   * <p>Runs each attempt in its own {@code REQUIRES_NEW} transaction and retries up to {@link
   * #MAX_PAYOUT_TOGGLE_ATTEMPTS} times when a concurrent writer wins, so the last writer wins
   * without a client version.
   *
   * @param operationId operation primary key
   * @param participantKey opaque participant key produced by {@link #getOperationPayouts}
   * @param paidOut new flag value
   * @return the paid-out status block of the updated participant
   * @throws NotFoundException when the operation or the participant key cannot be resolved
   * @throws ObjectOptimisticLockingFailureException when every attempt loses the race
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public OperationPayoutStatusDto setPayoutStatus(
      @NotNull UUID operationId, @NotNull String participantKey, boolean paidOut) {
    for (int attempt = 1; attempt < MAX_PAYOUT_TOGGLE_ATTEMPTS; attempt++) {
      try {
        return self.getObject()
            .setPayoutStatusWithinTransaction(operationId, participantKey, paidOut);
      } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException race) {
        log.debug(
            "Concurrent payout toggle race (attempt {}/{}) for operation {} — retrying",
            attempt,
            MAX_PAYOUT_TOGGLE_ATTEMPTS,
            operationId);
      }
    }
    return self.getObject().setPayoutStatusWithinTransaction(operationId, participantKey, paidOut);
  }

  /**
   * Performs one attempt of the payout toggle in its own {@code REQUIRES_NEW} transaction. Called
   * only by {@link #setPayoutStatus} through {@link #self}.
   *
   * @param operationId operation primary key
   * @param participantKey opaque participant key produced by {@link #getOperationPayouts}
   * @param paidOut new flag value
   * @return the paid-out status block of the updated participant
   * @throws NotFoundException when the operation or participant key cannot be resolved
   * @throws DataIntegrityViolationException when a concurrent writer already inserted the row
   * @throws ObjectOptimisticLockingFailureException when a concurrent writer already updated the
   *     row
   */
  @NotNull
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OperationPayoutStatusDto setPayoutStatusWithinTransaction(
      @NotNull UUID operationId, @NotNull String participantKey, boolean paidOut) {
    Operation operation =
        Entities.require(
            operationRepository.findWithMissionsAndParticipantsById(operationId),
            "Operation not found");

    Set<String> validKeys =
        OperationPayoutCalculator.computeParticipationBreakdown(operation)
            .participantNames()
            .keySet();
    if (!validKeys.contains(participantKey)) {
      throw new NotFoundException(
          "Participant '" + participantKey + "' is not part of operation " + operationId);
    }

    OperationPayoutStatus status =
        payoutStatusRepository
            .findByOperationIdAndParticipantKey(operationId, participantKey)
            .orElseGet(
                () -> {
                  OperationPayoutStatus s = new OperationPayoutStatus();
                  s.setOperation(operation);
                  s.setParticipantKey(participantKey);
                  return s;
                });

    status.setPaidOut(paidOut);
    if (paidOut) {
      status.setPaidOutAt(Instant.now());
      User actor = userService.getCurrentUser().orElse(null);
      status.setPaidOutByUser(actor);
    }

    payoutStatusRepository.save(status);
    auditService.record(
        AuditEventType.OPERATION_PAYOUT_TOGGLED,
        operationId,
        operation.getName(),
        null,
        AuditDetails.of("paidOut", paidOut));

    String paidOutByName =
        status.getPaidOutByUser() != null ? status.getPaidOutByUser().getEffectiveName() : null;
    return new OperationPayoutStatusDto(
        participantKey, status.isPaidOut(), status.getPaidOutAt(), paidOutByName);
  }

  /**
   * Loads the transfer-fee rate from {@code system_setting}, falling back to {@link
   * #DEFAULT_TRANSFER_FEE_RATE} with a WARN log when the value is absent, blank, not a number,
   * negative or {@code >= 1}.
   *
   * @return a validated rate in {@code [0, 1)}
   */
  @NotNull
  private BigDecimal resolveTransferFeeRate() {
    Optional<String> raw = systemSettingService.getSettingValue(TRANSFER_FEE_RATE_SETTING_KEY);
    if (raw.isEmpty() || raw.get().isBlank()) {
      log.warn(
          "System setting '{}' is missing or blank, falling back to default {}",
          TRANSFER_FEE_RATE_SETTING_KEY,
          DEFAULT_TRANSFER_FEE_RATE);
      return DEFAULT_TRANSFER_FEE_RATE;
    }
    try {
      BigDecimal parsed = new BigDecimal(raw.get().trim());
      if (parsed.signum() < 0 || parsed.compareTo(MAX_TRANSFER_FEE_RATE) >= 0) {
        log.warn(
            "System setting '{}'={} is out of range [0, 1), falling back to default {}",
            TRANSFER_FEE_RATE_SETTING_KEY,
            parsed,
            DEFAULT_TRANSFER_FEE_RATE);
        return DEFAULT_TRANSFER_FEE_RATE;
      }
      return parsed;
    } catch (NumberFormatException e) {
      log.warn(
          "System setting '{}'='{}' is not a valid decimal, falling back to default {}",
          TRANSFER_FEE_RATE_SETTING_KEY,
          raw.get(),
          DEFAULT_TRANSFER_FEE_RATE);
      return DEFAULT_TRANSFER_FEE_RATE;
    }
  }
}
