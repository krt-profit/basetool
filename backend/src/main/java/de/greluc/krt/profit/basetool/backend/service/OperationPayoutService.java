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
 * Payout computation, the per-participant paid-out toggle and the in-game transfer-fee resolution
 * for the {@code operation} aggregate. Split out of {@code OperationService} (audit Thema 7, #14)
 * so the operation CRUD/reads and the heavier payout engine live in focused services; the plain
 * CRUD (list, search, picker, create, update, delete) stays in {@code OperationService}.
 *
 * <p>The pure aggregate math — total sum, per-owner out-of-pocket reimbursements and the
 * per-participant attendance breakdown — lives in the stateless {@link OperationPayoutCalculator};
 * this service loads the ledger / refinery orders / participant graph, merges in the persisted
 * paid-out status and assembles the DTOs.
 *
 * <p>The payout view is the complex read path here: {@link #getOperationPayouts(UUID)} computes the
 * per-participant time share AND the actual money number per participant. The money formula treats
 * out-of-pocket expenses (mission EXPENSE entries owned by the participant + refinery orders' costs
 * where they are the owner) as a "reimbursement off the top" before the remaining {@code totalSum}
 * is split per participation percentage among PAYOUT participants. DONATE participants still get
 * their personal reimbursement (their own money) but their share is not distributed. Finally, an
 * in-game banking fee is deducted from every participant's gross payout so the displayed amount
 * matches what their mobiGlas will actually receive. The fee rate is runtime-editable: it lives
 * under the {@code operation.transfer_fee_rate} key in {@code system_setting} (seeded to 0.005 =
 * 0.5% by Flyway), can be tuned from {@code /admin/settings} by officers and admins, and degrades
 * to {@link #DEFAULT_TRANSFER_FEE_RATE} when the row is missing, blank or unparseable. {@link
 * #setPayoutStatus(UUID, String, boolean)} provides the mission-manager toggle that records whether
 * a participant has been paid out; it is a non-transactional orchestrator retrying a {@code
 * REQUIRES_NEW} inner method so the last-writer-wins race on the {@code @Version}ed,
 * uniquely-indexed status row is resolved by retry rather than a client version echo (#1111).
 * Operationen is an audited area — the toggle records {@link
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
   * Fallback in-game banking fee rate used when the {@code operation.transfer_fee_rate} system
   * setting is absent, blank or unparseable. Mirrors Star Citizen's documented Spectrum / mobiGlas
   * banking charge of 0.5% on every aUEC transfer to the recipient. Kept as a hard fallback so a
   * truncated or accidentally-deleted setting row degrades gracefully to the historical default
   * instead of crashing the payout view (which is gated behind authentication and used by mission
   * managers settling operations — silent zero would be far worse than a possibly-stale rate).
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
   * Self-reference used to invoke {@link #setPayoutStatusWithinTransaction} through the Spring
   * proxy so each retry of {@link #setPayoutStatus} opens its OWN {@code REQUIRES_NEW} transaction.
   * A direct {@code this} call would be self-invocation and skip the proxy, running every attempt
   * in one transaction — which is fatal here, because a losing writer's {@link
   * DataIntegrityViolationException} poisons the whole transaction so no same-transaction retry
   * could ever commit. An {@link ObjectProvider} defers the lookup, avoiding an eager
   * self-injection cycle at construction (#1111).
   */
  private final ObjectProvider<OperationPayoutService> self;

  /**
   * Total attempts (one initial + retries) the payout toggle makes against a concurrent same-row
   * writer before giving up and surfacing the conflict. Three is ample: the first losing writer
   * loses the INSERT / {@code @Version} race, but by the retry the winner has committed the row, so
   * a second attempt loads it and UPDATEs in place. A further loss needs yet another writer
   * toggling the exact same {@code (operation, participant)} within microseconds — vanishingly
   * unlikely.
   */
  private static final int MAX_PAYOUT_TOGGLE_ATTEMPTS = 3;

  /**
   * Computes the per-participant payout breakdown for the operation.
   *
   * <p>Pulls the operation with its missions / participants pre-fetched, then loads finance entries
   * and refinery orders by mission id so the percentage AND the money number can be derived in one
   * pass. The percentage is the participant's clamped attendance time over the operation's clamped
   * attendance time (mirrors the previous behavior); the money number is {@code
   * round(personalExpenses + sharePayout − transferFee)} — final HALF_UP rounding to whole aUEC,
   * because Star Citizen's mobiGlas does not accept fractional credits in a transfer. The
   * components below are tracked at two-decimal precision so the breakdown sub-labels stay
   * auditable; only the final {@code payoutAmount} is collapsed to scale 0:
   *
   * <ul>
   *   <li><b>personalExpenses</b> reimburses each participant for the expenses attributed to them —
   *       mission EXPENSE entries where they are the {@code participant} plus refinery orders'
   *       {@code expenses + otherExpenses} where they are the {@code owner}.
   *   <li><b>sharePayout</b> is {@code totalSum × percentage / 100} for PAYOUT participants and
   *       {@link BigDecimal#ZERO} for DONATE participants. Their share is contributed to the org
   *       but the reimbursement is still paid out (it is the participant's own money returned).
   *   <li><b>transferFee</b> is {@code (personalExpenses + sharePayout) × transferFeeRate} — Star
   *       Citizen's in-game banking deducts a small fraction from any aUEC transfer to the
   *       recipient, so this fee is subtracted from the gross payout to show what actually lands in
   *       the participant's mobiGlas. The rate is runtime-editable via the {@code
   *       operation.transfer_fee_rate} system setting (default 0.005 = 0.5%, fallback applied when
   *       the row is absent or unparseable). Applies to PAYOUT and DONATE participants alike
   *       (DONATE participants only receive their reimbursement, but that transfer also incurs the
   *       fee).
   * </ul>
   *
   * <p>The {@code paidOut*} fields are merged in from {@link OperationPayoutStatus} rows by
   * participant key; absence of a row is treated as {@code paidOut=false}. Output is sorted
   * alphabetically by participant name.
   *
   * <p>Uses {@link OperationRepository#findWithMissionsAndParticipantsById} with an explicit
   * {@code @EntityGraph} so the loop's {@code .getMissions()/.getParticipants()/.getUser()} never
   * triggers lazy SELECTs — otherwise {@code 1 + N + N*M} round trips for {@code N} missions ×
   * {@code M} participants each. Refinery orders are fetched via {@code @EntityGraph} on the
   * repository method so the owner is eagerly loaded for the per-owner cost attribution.
   *
   * @param id operation primary key
   * @return per-participant payout breakdown, sorted by participant name
   * @throws NotFoundException when no match
   */
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
      // Full pool share earned by attendance: a PAYOUT participant receives it as shareAmount, a
      // DONATE participant forgoes it (shareAmount = 0) and contributes it to the org. That
      // contributed slice is surfaced as donatedAmount and summed into totalDonations.
      // Computed once so the PAYOUT/DONATE split cannot drift apart.
      BigDecimal fullShare =
          totalSum
              .multiply(BigDecimal.valueOf(percentage))
              .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
      BigDecimal zeroShare = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
      BigDecimal shareAmount = donating ? zeroShare : fullShare;
      BigDecimal donatedAmount = donating ? fullShare : zeroShare;
      BigDecimal grossPayout = personalExpenses.add(shareAmount);
      // Star Citizen banking deducts a small percentage from any aUEC transfer to the recipient —
      // model that here so the displayed Auszahlungsbetrag is what actually lands in the
      // participant's mobiGlas. The rate is loaded once per call from system_setting (see
      // resolveTransferFeeRate) so officers/admins can adjust it without a redeploy. Rounded
      // HALF_UP to match the other monetary fields. Negative gross would produce a negative fee
      // mathematically; in practice grossPayout is always >= 0 (expenses are positive, share is
      // >= 0), so HALF_UP rounding here mirrors the rest of the pipeline.
      BigDecimal transferFee =
          grossPayout.multiply(transferFeeRate).setScale(2, RoundingMode.HALF_UP);
      // Kaufmaennisch (HALF_UP) auf ganze aUEC runden: Star Citizen kennt im mobiGlas keine
      // Nachkommastellen, jeder Transfer landet als Integer beim Empfaenger. Damit der angezeigte
      // Auszahlungsbetrag dem entspricht, was der Mission Manager tatsaechlich anweisen muss,
      // rundet das Backend hier final auf scale 0. Die Sub-Labels (Auslagen, Ueberweisungsgebuehr)
      // behalten ihre 2 Nachkommastellen — sie bleiben transparenter Breakdown, nicht der "Pay X"-
      // Betrag — was geringfuegige Diskrepanzen beim Nachrechnen erlaubt, dafuer aber den Hauptwert
      // glatt haelt.
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
        Comparator.comparing(OperationPayoutDto::participantName, String.CASE_INSENSITIVE_ORDER));
    return result;
  }

  /**
   * Returns the operation payout breakdown together with the operation-wide donation total.
   *
   * <p>Thin aggregation wrapper over {@link #getOperationPayouts(UUID)}: it builds the payout rows
   * once (a single load of the participant graph) and sums every row's {@link
   * OperationPayoutDto#donatedAmount()} into {@code totalDonations}. The total therefore equals the
   * sum of the per-donor donated shares shown in the table — there is no separate, independently
   * rounded computation that could drift from the rows. PAYOUT rows contribute {@link
   * BigDecimal#ZERO}, so the total is exactly the pool slice the DONATE participants contributed to
   * the org rather than received. This is the canonical read path for the operation-detail payout
   * panel.
   *
   * @param id operation primary key
   * @return the payout rows (sorted by participant name) plus the aggregated donation total
   * @throws NotFoundException when no operation matches the id
   */
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
   * Reduces the breakdown to the caller's own row when they reached the operation <em>only</em>
   * through the participant escape.
   *
   * <p>{@code canSeeOperation} - the endpoint's gate - admits two very different callers: somebody
   * in the operation's org-unit scope, and somebody who merely participated in one of its linked
   * missions. The second key is self-issuable ({@code POST /api/v1/missions/&#123;id&#125;/join} is
   * open for every non-internal mission of every org unit), so honouring it with the full breakdown
   * meant one request bought a foreign unit's entire payout table: per participant the callsign,
   * share percentage, personal expenses, share and donated amounts, transfer fee, net payout and
   * the confirming officer.
   *
   * <p>The escape itself stays - a participant may still open the operation and read <strong>their
   * own</strong> payout, which is what ADR-0006 granted it for. What changes is that the escape no
   * longer carries everybody else's row with it. {@code totalDonations} is recomputed over the
   * reduced list by the caller, so the operation-wide aggregate does not leak either.
   *
   * @param operationId the operation being read.
   * @param payouts the full breakdown.
   * @return the full list for a scope-visible caller, otherwise only the caller's own row.
   */
  private List<OperationPayoutDto> restrictToOwnRowIfEscapeOnly(
      @NotNull UUID operationId, @NotNull List<OperationPayoutDto> payouts) {
    if (ownerScopeService.canSeeOperationLedger(operationId)) {
      return payouts;
    }
    // The participant key is the user id for a registered member (OperationPayoutCalculator
    // #participantKey); an escape-only caller is always registered, since the escape requires an
    // authenticated participation row.
    String ownKey = authHelperService.currentUserId().map(UUID::toString).orElse(null);
    if (ownKey == null) {
      return List.of();
    }
    return payouts.stream().filter(row -> ownKey.equals(row.participantId())).toList();
  }

  /**
   * Toggles the paid-out flag for a single participant of an operation, recording the acting user
   * and timestamp. Materializes a new {@link OperationPayoutStatus} row on the first toggle;
   * subsequent toggles update the row in place.
   *
   * <p><b>Last-writer-wins under concurrency, for real.</b> The row carries a JPA {@code @Version}
   * and a unique constraint on {@code (operation_id, participant_key)}, so two leads toggling the
   * same never-yet-paid participant at once do NOT both succeed: one loses the INSERT (constraint)
   * or the UPDATE ({@code @Version}) race and throws. This method therefore runs each attempt in
   * its own {@code REQUIRES_NEW} transaction (via {@link #self}) and retries up to {@link
   * #MAX_PAYOUT_TOGGLE_ATTEMPTS} times on {@link DataIntegrityViolationException} / {@link
   * ObjectOptimisticLockingFailureException}: by the retry the winner has committed the row, so the
   * loser reloads it and UPDATEs in place. No client-supplied version is required (the field is a
   * boolean); the retry — not the field's nature — is what makes the toggle idempotent. Previously
   * the Javadoc claimed this was intrinsic and the bare save simply 409'd the loser, which the
   * frontend proxy then mis-mapped to a 500 (#1111).
   *
   * @param operationId operation primary key
   * @param participantKey opaque participant key produced by {@link #getOperationPayouts}
   * @param paidOut new flag value
   * @return the paid-out status block for the updated participant, for patching a single "Bezahlt"
   *     cell without re-running the full payout computation
   * @throws NotFoundException when the operation does not exist or the participant key cannot be
   *     resolved against the operation's current participant list
   * @throws ObjectOptimisticLockingFailureException only if every attempt loses the race — a
   *     persistent hot-row contention the proxy still maps to a 409, never a 500
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public OperationPayoutStatusDto setPayoutStatus(
      @NotNull UUID operationId, @NotNull String participantKey, boolean paidOut) {
    // Retry the toggle across FRESH transactions. Each attempt is REQUIRES_NEW (see self): a losing
    // writer's constraint / version violation poisons its own transaction, so the only correct
    // retry
    // is a brand-new one. All but the final attempt swallow the race and loop; the final attempt
    // lets
    // a persistent race propagate so the proxy surfaces a truthful 409, not a pretend success.
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
   * Performs one attempt of the payout toggle inside its own {@code REQUIRES_NEW} transaction — the
   * find-or-create + save + audit + re-render body that {@link #setPayoutStatus} retries. Kept
   * separate (and invoked through {@link #self}) precisely so a constraint / version violation
   * rolls back only this attempt's transaction and the caller can retry in a clean one (#1111).
   * Must never be called directly by application code — go through {@link #setPayoutStatus}.
   *
   * @param operationId operation primary key
   * @param participantKey opaque participant key produced by {@link #getOperationPayouts}
   * @param paidOut new flag value
   * @return the paid-out status block for the updated participant
   * @throws NotFoundException when the operation or participant key cannot be resolved
   * @throws DataIntegrityViolationException when a concurrent writer already inserted the row
   * @throws ObjectOptimisticLockingFailureException when a concurrent writer already updated the
   *     row
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OperationPayoutStatusDto setPayoutStatusWithinTransaction(
      @NotNull UUID operationId, @NotNull String participantKey, boolean paidOut) {
    // Load the operation with its mission-participant graph (bounded by participant count, NOT the
    // finance ledger) to validate the key and attach the status row to a managed operation. The
    // paid-out toggle does NOT re-run the full payout computation (the double finance/refinery
    // load-all + the money math) — flipping the flag never changes any amount, so the caller
    // patches
    // just the "Bezahlt" cell from the returned status block (#1121, operation-side ADR-0078 gap).
    Operation operation =
        Entities.require(
            operationRepository.findWithMissionsAndParticipantsById(operationId),
            "Operation not found");

    // Same key set the read path exposes (both derive from computeParticipationBreakdown), so the
    // 404-on-unknown-participant behavior is preserved without re-running getOperationPayouts.
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
    // Note: when toggling back to paidOut=false we deliberately keep paidOutAt / paidOutByUser as
    // the last "was paid" trace — see V78 migration's column comments. The frontend renders the
    // current paidOut flag, the audit fields are only inspected when paidOut=true.

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
   * Loads and validates the runtime-editable in-game transfer-fee rate from {@code system_setting}.
   * Falls back to {@link #DEFAULT_TRANSFER_FEE_RATE} when the row is absent, blank, not a number,
   * negative or {@code >= 1} (which would zero-out or invert every payout). The fallback is logged
   * at WARN so an operator sees the misconfiguration in the access log instead of silently working
   * off a stale default. Called once per {@link #getOperationPayouts(UUID)} invocation — the
   * underlying repository hit is a single PK lookup on a tiny table, so caching is unnecessary and
   * would only introduce stale-value risk after admin edits.
   *
   * @return a non-null, validated rate in the range {@code [0, 1)}; either the persisted setting
   *     value or {@link #DEFAULT_TRANSFER_FEE_RATE} when the persisted value is unusable
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
