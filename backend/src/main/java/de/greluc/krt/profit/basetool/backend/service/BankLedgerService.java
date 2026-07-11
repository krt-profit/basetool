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

import static de.greluc.krt.profit.basetool.backend.util.BankAmounts.plain;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.BankTransaction;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankHolderTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank's booking engine (epic #556): books deposits, withdrawals, account-to-account transfers,
 * holder→holder Umbuchungen, reversals and the admin wipe reset onto the <strong>two</strong>
 * append-only ledgers (REQ-BANK-004, ADR-0010/0039) — account legs in {@code bank_posting}, holder
 * legs in {@code bank_holder_posting}.
 *
 * <p>This orchestrator owns the booking flow, fee arithmetic, counterparty resolution and the audit
 * trail; the mechanical persistence (row locking, holder resolution, ledger inserts) is delegated
 * to {@link BankPostingWriter} and every pre-persist validation to {@link BankBookingGuards}
 * (#1253). Both collaborators run inside the transaction each {@code book*} / {@code reverse} /
 * {@code reset} entry point opens, so the concurrency contract below is unchanged by the split.
 *
 * <p><strong>Concurrency &amp; overdraft contract.</strong> Every booking that touches an account
 * first locks the affected account row(s) via {@link BankPostingWriter#lockAccount} — multi-account
 * bookings in ascending id order so concurrent flows cannot deadlock — and only then reads the
 * balance it validates against. Because all value movement on an account serializes on that lock,
 * the <strong>account</strong> no-overdraft invariant (REQ-BANK-006) cannot be raced. The
 * <strong>holder</strong> dimension is deliberately <em>unconstrained</em> (ADR-0039): a holder
 * balance may go negative — a custodian fronts his own money, reconciled later by a {@link
 * #bookHolderTransfer} Umbuchung — so no booking path checks holder coverage. The ledger rows
 * themselves are insert-only: no {@code @Version} churn, no {@code save()}-on-managed-entity traps.
 *
 * <p><strong>Holder activity.</strong> Postings that ADD money to a holder's stash require the
 * holder to be active (deposit receiver, transfer destination); postings that REMOVE money are
 * allowed on deactivated holders so a stash can be wound down. The holder→holder Umbuchung — the
 * reconciliation tool — ignores the active flag in both directions so a deactivated holder can be
 * brought back to zero. Reversals are exempt — they restore a prior, already-audited state.
 *
 * <p>Every booking appends exactly one audit row in the same transaction ({@link
 * BankAuditService}); an audit failure rolls the booking back (REQ-BANK-012).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BankLedgerService {

  private final BankAccountRepository accountRepository;
  private final BankTransactionRepository transactionRepository;
  private final BankPostingRepository postingRepository;
  private final BankHolderPostingRepository holderPostingRepository;
  private final BankAuditService bankAuditService;
  private final BankTransferFeeService transferFeeService;
  private final UserRepository userRepository;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final BankPostingWriter writer;
  private final BankBookingGuards guards;

  /**
   * Books a deposit (REQ-BANK-004): one positive account leg on the receiving account and one
   * positive holder leg naming the holder who physically received the money. When the payload opts
   * into a split (REQ-BANK-043) the booking fans out across the squadron accounts via {@link
   * #bookSplitDeposit(BankDepositRequest, BankHolder)} instead. A non-split deposit optionally
   * records the <strong>counterparty</strong> — the Einzahler who handed the money in, and the org
   * unit they belong to — on the transaction header (REQ-BANK-044), distinct from the receiving
   * holder.
   *
   * @param request validated deposit payload
   * @return acknowledgement of the created transaction
   * @throws NotFoundException when account, holder or the named counterparty user do not exist
   * @throws BankConflictException with {@code BANK_ACCOUNT_CLOSED} on a closed account, {@code
   *     BANK_HOLDER_INACTIVE} on a deactivated holder, or {@code BANK_SPLIT_NO_TARGETS} / {@code
   *     BANK_SPLIT_TOO_SMALL} when a requested split cannot be honoured
   * @throws BadRequestException when a counterparty org unit is named that is not one of the
   *     counterparty user's memberships (REQ-BANK-044)
   */
  @Transactional
  public BankTransactionDto bookDeposit(@NotNull BankDepositRequest request) {
    if (request.splitEnabled()) {
      BankHolder holder = writer.requireHolder(request.holderId());
      guards.requireActiveHolder(holder);
      return bookSplitDeposit(request, holder);
    }
    BankAccount account = writer.lockAccount(request.accountId());
    guards.requireActive(account);
    BankHolder holder = writer.requireHolder(request.holderId());
    guards.requireActiveHolder(holder);
    CounterpartySnapshot counterparty =
        resolveCounterparty(
            request.counterpartyUserId(),
            request.counterpartyExternalName(),
            request.counterpartyOrgUnitId());

    Instant now = Instant.now();
    // A deposit carries no bank-borne fee: whoever pays money IN bears their own in-game transfer
    // fee, so the full amount lands on the account and the holder's stash (REQ-BANK-033).
    BankTransaction tx =
        writer.persistTransaction(
            BankTransactionType.DEPOSIT,
            request.note(),
            null,
            null,
            BigDecimal.ZERO,
            now,
            counterparty);
    writer.persistAccountPosting(tx, account, request.amount(), now);
    writer.persistHolderPosting(tx, holder, request.amount(), now);
    bankAuditService.record(
        BankAuditEventType.DEPOSIT_BOOKED,
        account.getId(),
        tx.getId(),
        counterparty == null ? null : counterparty.userId(),
        "+"
            + request.amount().toPlainString()
            + " aUEC @"
            + holder.getHandle()
            + counterpartyDetail(counterparty, "<-"));
    return toDto(tx);
  }

  /**
   * Books a <strong>split</strong> deposit (REQ-BANK-043): one {@code DEPOSIT} transaction whose
   * gross lands once on the named holder's stash (a single positive holder leg, REQ-BANK-003/-004)
   * but is distributed across several account legs — a percentage slice spread evenly by count over
   * every active squadron account (excluding the named account), with the named account credited
   * the remainder. A deposit is fee-free (REQ-BANK-033), so no in-game fee applies.
   *
   * <p>The slice is {@code round(gross × percent / 100)} to whole aUEC (HALF_UP). It is split with
   * the largest-remainder rule: {@code base = floor(slice / N)}, and the leftover {@code slice −
   * base·N} aUEC go one each to the first accounts by ascending id, so the per-account amounts are
   * as even as possible, stay whole and sum back to the slice exactly. The named account is
   * credited {@code gross − slice}, so every account leg plus the named leg sums to the gross —
   * equal to the single holder leg. Zero-amount legs are dropped (a 100 % split books no named leg;
   * a slice smaller than the target count credits only the first {@code slice} accounts).
   *
   * <p>All affected accounts (named + squadrons) are pessimistically locked in ascending id order —
   * the same global lock order every other multi-account flow uses (transfer, reversal, wipe) — so
   * concurrent bookings cannot deadlock. The named account must be active; squadron accounts that
   * closed in the race window are dropped from the distribution.
   *
   * @param request the validated split deposit payload ({@code splitEnabled} set, {@code
   *     splitPercent} present)
   * @param holder the already-resolved, active receiving holder
   * @return acknowledgement of the created transaction
   * @throws NotFoundException when the named account does not exist
   * @throws BankConflictException with {@code BANK_ACCOUNT_CLOSED} on a closed named account,
   *     {@code BANK_SPLIT_TOO_SMALL} when the slice rounds below 1 aUEC, or {@code
   *     BANK_SPLIT_NO_TARGETS} when no active squadron account remains to distribute to
   */
  private BankTransactionDto bookSplitDeposit(
      @NotNull BankDepositRequest request, @NotNull BankHolder holder) {
    BigDecimal gross = request.amount();
    // slice = round(gross * percent / 100) to whole aUEC; the named account keeps gross - slice.
    BigDecimal slice =
        gross
            .multiply(request.splitPercent())
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    if (slice.signum() <= 0) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_SPLIT_TOO_SMALL,
          "The split percentage of the amount rounds to less than 1 aUEC",
          Map.of("amount", plain(gross), "percent", plain(request.splitPercent())));
    }

    // Enumerate the active squadron accounts (ORG_UNIT + kind SQUADRON), excluding the named
    // account, then lock the named account and the targets together in ascending id order.
    List<UUID> squadronIds =
        accountRepository
            .findByTypeAndStatusOrderById(BankAccountType.ORG_UNIT, BankAccountStatus.ACTIVE)
            .stream()
            .filter(a -> a.getOrgUnit() != null && a.getOrgUnit().getKind() == OrgUnitKind.SQUADRON)
            .map(BankAccount::getId)
            .filter(id -> !id.equals(request.accountId()))
            .toList();
    if (squadronIds.isEmpty()) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_SPLIT_NO_TARGETS,
          "There is no active squadron account to distribute the split to");
    }

    TreeSet<UUID> lockOrder = new TreeSet<>(squadronIds);
    lockOrder.add(request.accountId());
    Map<UUID, BankAccount> locked = new LinkedHashMap<>();
    for (UUID id : lockOrder) {
      locked.put(id, writer.lockAccount(id));
    }
    BankAccount named = locked.get(request.accountId());
    guards.requireActive(named);
    // Drop any squadron account that closed between the unlocked enumeration and the lock; the
    // distribution always runs over currently-active targets only.
    List<UUID> targets =
        squadronIds.stream()
            .filter(id -> locked.get(id).getStatus() == BankAccountStatus.ACTIVE)
            .sorted()
            .toList();
    if (targets.isEmpty()) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_SPLIT_NO_TARGETS,
          "There is no active squadron account to distribute the split to");
    }

    Map<UUID, BigDecimal> shares = distributeEvenly(targets, slice);
    BigDecimal distributed = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal namedShare = gross.subtract(distributed);

    CounterpartySnapshot counterparty =
        resolveCounterparty(
            request.counterpartyUserId(),
            request.counterpartyExternalName(),
            request.counterpartyOrgUnitId());
    Instant now = Instant.now();
    BankTransaction tx =
        writer.persistTransaction(
            BankTransactionType.DEPOSIT,
            request.note(),
            null,
            null,
            BigDecimal.ZERO,
            now,
            counterparty);
    // The named account keeps the remainder; a 100 % split leaves nothing for it, so its leg is
    // dropped (a posting is never zero, REQ-BANK-004).
    if (namedShare.signum() > 0) {
      writer.persistAccountPosting(tx, named, namedShare, now);
    }
    shares.forEach((id, share) -> writer.persistAccountPosting(tx, locked.get(id), share, now));
    // The money physically landed once with one custodian, so a single holder leg over the gross
    // (REQ-BANK-003); the split is purely an account-side allocation.
    writer.persistHolderPosting(tx, holder, gross, now);
    bankAuditService.record(
        BankAuditEventType.DEPOSIT_SPLIT_BOOKED,
        named.getId(),
        tx.getId(),
        counterparty == null ? null : counterparty.userId(),
        "+"
            + gross.toPlainString()
            + " aUEC @"
            + holder.getHandle()
            + counterpartyDetail(counterparty, "<-")
            + " split "
            + plain(request.splitPercent())
            + "% ("
            + plain(distributed)
            + " aUEC -> "
            + targets.size()
            + " Staffelkonten)");
    return toDto(tx);
  }

  /**
   * Distributes a whole-aUEC slice as evenly as possible across the given targets with the
   * largest-remainder rule (REQ-BANK-044): each target gets {@code floor(slice / N)} and the
   * leftover {@code slice − base·N} aUEC go one each to the first targets in the supplied order.
   * Zero shares (when {@code base} is 0 and the target is past the remainder cut-off) are omitted,
   * so the result never carries a zero leg and its values always sum to {@code slice} exactly.
   *
   * @param targets the receiving account ids, already ordered deterministically (ascending id)
   * @param slice the positive whole-aUEC amount to distribute
   * @return an insertion-ordered map of target id → positive whole-aUEC share
   */
  @NotNull
  private static Map<UUID, BigDecimal> distributeEvenly(
      @NotNull List<UUID> targets, @NotNull BigDecimal slice) {
    int n = targets.size();
    BigDecimal count = BigDecimal.valueOf(n);
    BigDecimal base = slice.divideToIntegralValue(count);
    int remainder = slice.subtract(base.multiply(count)).intValueExact();
    Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      BigDecimal share = i < remainder ? base.add(BigDecimal.ONE) : base;
      if (share.signum() > 0) {
        shares.put(targets.get(i), share);
      }
    }
    return shares;
  }

  /**
   * Books a withdrawal (REQ-BANK-004): one negative account leg on the paying account and one
   * negative holder leg naming the holder who physically paid the money out. Guarded by the
   * no-overdraft rule at <strong>account</strong> level only (REQ-BANK-006) — the holder may go
   * negative.
   *
   * <p>By default (on-top, ADR-0052 superseding ADR-0041, REQ-BANK-033) the entered amount is what
   * the external recipient must <strong>receive</strong>; the in-game transfer fee is added on top
   * and the account and holder's stash are debited the gross ({@code amount + fee}). With the
   * <strong>fee-inclusive</strong> mode ({@code request.feeInclusive()}, #999) the entered amount
   * is instead the gross <strong>debited</strong> and the recipient receives {@code amount - fee}
   * (rejected with {@code BANK_FEE_EXCEEDS_AMOUNT} when {@code amount - fee <= 0}). The overdraft
   * guard runs against whatever is actually debited, so the account may not be driven negative; the
   * holder is not out of pocket — the fee is borne by the debited account, not their private money.
   *
   * <p>Optionally records the <strong>counterparty</strong> — the Empf&auml;nger who received the
   * payout, and the org unit they belong to — on the transaction header (REQ-BANK-044), distinct
   * from the paying holder.
   *
   * @param request validated withdrawal payload
   * @return acknowledgement of the created transaction
   * @throws NotFoundException when account, holder or the named counterparty user do not exist
   * @throws BankConflictException with {@code BANK_ACCOUNT_CLOSED} or {@code BANK_OVERDRAFT}
   * @throws BadRequestException when a counterparty org unit is named that is not one of the
   *     counterparty user's memberships (REQ-BANK-044)
   */
  @Transactional
  public BankTransactionDto bookWithdrawal(@NotNull BankWithdrawalRequest request) {
    BankAccount account = writer.lockAccount(request.accountId());
    guards.requireActive(account);
    BankBookingGuards.requireDebitJustification(account, request.justification());
    final BankHolder holder = writer.requireHolder(request.holderId());
    CounterpartySnapshot counterparty =
        resolveCounterparty(
            request.counterpartyUserId(),
            request.counterpartyExternalName(),
            request.counterpartyOrgUnitId());

    // Fee mode (REQ-BANK-033, #999). The fee is always round(entered amount x rate). On-top
    // (default):
    // the source is debited amount + fee and the recipient gets the full amount. Inclusive: the
    // source
    // is debited exactly the entered amount and the recipient gets amount - fee (rejected if that
    // leaves nothing to arrive). The overdraft guard runs against whatever is actually debited.
    BigDecimal fee = transferFeeService.feeOn(request.amount());
    BigDecimal debit =
        request.feeInclusive() ? request.amount() : transferFeeService.totalDebit(request.amount());
    if (request.feeInclusive()) {
      guards.requireAmountExceedsFee(request.amount(), fee);
    }
    guards.requireAccountCoverage(account, debit);

    Instant now = Instant.now();
    BankTransaction tx =
        writer.persistTransaction(
            BankTransactionType.WITHDRAWAL,
            request.note(),
            request.justification(),
            null,
            fee,
            now,
            counterparty);
    writer.persistAccountPosting(tx, account, debit.negate(), now);
    writer.persistHolderPosting(tx, holder, debit.negate(), now);
    bankAuditService.record(
        BankAuditEventType.WITHDRAWAL_BOOKED,
        account.getId(),
        tx.getId(),
        counterparty == null ? null : counterparty.userId(),
        "-"
            + plain(debit)
            + " aUEC @"
            + holder.getHandle()
            + counterpartyDetail(counterparty, "->")
            + feeDetail(fee, request.feeInclusive()));
    return toDto(tx);
  }

  /**
   * Books an account-to-account transfer (REQ-BANK-011): two account legs and two holder legs —
   * value moves between two <strong>different</strong> accounts and the physical custody moves with
   * it. The caller's {@code can_transfer} on the source is gated at the controller; the
   * <em>destination visibility</em> rule (the employee must hold any grant on the destination
   * account) is enforced here via the supplied check. The source account is guarded against
   * overdraft; the holder dimension is not (ADR-0039).
   *
   * <p>When the custody actually changes hands (source holder ≠ destination holder), a real in-game
   * transfer happens, so the in-game fee (ADR-0052 superseding ADR-0041, REQ-BANK-033) applies. By
   * default (on-top) the source account and holder are debited the gross ({@code amount + fee})
   * while the destination is credited the full entered amount. With the
   * <strong>fee-inclusive</strong> mode ({@code request.feeInclusive()}, #999) the source is
   * debited exactly the entered amount and the destination is credited {@code amount - fee}
   * (rejected with {@code BANK_FEE_EXCEEDS_AMOUNT} when {@code amount - fee <= 0}). Either way the
   * account legs net to {@code -fee}, preserving the ledger-integrity invariant. The source
   * overdraft guard runs against the actual debit. A same-holder transfer moves no money in-game
   * (the holder merely re-labels which account owns it), so it is fee-free — {@code feeInclusive}
   * has no effect — and both legs net to zero as before.
   *
   * @param request validated transfer payload (source and destination accounts must differ)
   * @param destinationVisible whether the caller may see the destination account (pre-computed by
   *     the controller from {@code BankSecurityService.canSee})
   * @return acknowledgement of the created transaction
   * @throws NotFoundException when an account or holder does not exist
   * @throws AccessDeniedException when the destination is not visible to the caller
   * @throws BankConflictException with {@code BANK_SELF_TRANSFER}, {@code BANK_ACCOUNT_CLOSED},
   *     {@code BANK_HOLDER_INACTIVE} or {@code BANK_OVERDRAFT}
   */
  @Transactional
  public BankTransactionDto bookTransfer(
      @NotNull BankTransferRequest request, boolean destinationVisible) {
    if (request.sourceAccountId().equals(request.destinationAccountId())) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_SELF_TRANSFER,
          "Source and destination account of a transfer must differ"
              + " (use a holder Umbuchung to move custody between holders)");
    }
    if (!destinationVisible) {
      throw new AccessDeniedException("Destination account is not visible to the caller");
    }

    BankAccount source;
    BankAccount destination;
    if (request.sourceAccountId().compareTo(request.destinationAccountId()) < 0) {
      source = writer.lockAccount(request.sourceAccountId());
      destination = writer.lockAccount(request.destinationAccountId());
    } else {
      destination = writer.lockAccount(request.destinationAccountId());
      source = writer.lockAccount(request.sourceAccountId());
    }
    guards.requireActive(source);
    guards.requireActive(destination);
    BankBookingGuards.requireDebitJustification(source, request.justification());

    final BankHolder sourceHolder = writer.requireHolder(request.sourceHolderId());
    BankHolder destinationHolder = writer.requireHolder(request.destinationHolderId());
    guards.requireActiveHolder(destinationHolder);

    // Custody only physically moves — and thus incurs the in-game fee — when source and destination
    // holders differ; a same-holder transfer is a pure re-label (fee-free, debit = amount). When
    // the
    // holder changes the fee is added on top: the source is debited the gross (amount + fee) while
    // the destination is credited the full entered amount (ADR-0052). The overdraft guard runs
    // against the gross so the fee can never drive the source account negative.
    final boolean holderChanges = !sourceHolder.getId().equals(destinationHolder.getId());
    BigDecimal fee = holderChanges ? transferFeeService.feeOn(request.amount()) : BigDecimal.ZERO;
    // Fee mode (REQ-BANK-033, #999), effective only on a holder-changing transfer (a same-holder
    // transfer is fee-free, so both modes debit and credit the plain amount). On-top (default):
    // source
    // debited amount + fee, destination credited the full amount. Inclusive: source debited exactly
    // the entered amount, destination credited amount - fee (rejected if that leaves nothing). Both
    // net to -fee across the account legs, so the ledger-integrity invariant (SUM(legs) =
    // -transfer_fee, ADR-0052) holds in either mode. The overdraft guard runs against the debit.
    final boolean inclusive = request.feeInclusive() && holderChanges;
    BigDecimal debit = inclusive ? request.amount() : request.amount().add(fee);
    final BigDecimal credit = inclusive ? request.amount().subtract(fee) : request.amount();
    if (inclusive) {
      guards.requireAmountExceedsFee(request.amount(), fee);
    }
    guards.requireAccountCoverage(source, debit);

    Instant now = Instant.now();
    BankTransaction tx =
        writer.persistTransaction(
            BankTransactionType.TRANSFER,
            request.note(),
            request.justification(),
            null,
            fee,
            now,
            null);
    writer.persistAccountPosting(tx, source, debit.negate(), now);
    writer.persistAccountPosting(tx, destination, credit, now);
    writer.persistHolderPosting(tx, sourceHolder, debit.negate(), now);
    writer.persistHolderPosting(tx, destinationHolder, credit, now);
    bankAuditService.record(
        BankAuditEventType.TRANSFER_BOOKED,
        source.getId(),
        tx.getId(),
        null,
        plain(debit)
            + " aUEC -> "
            + destination.getAccountNo()
            + " ("
            + sourceHolder.getHandle()
            + " -> "
            + destinationHolder.getHandle()
            + ")"
            + feeDetail(fee, inclusive));
    return toDto(tx);
  }

  /**
   * Books a holder→holder Umbuchung (REQ-BANK-031, ADR-0039): two holder legs and
   * <strong>no</strong> account leg — pure custody reconciliation between two players so the bank
   * staff stay payout-capable. The source holder may go negative; the active flag is ignored in
   * both directions so a deactivated holder's residual can be reconciled to zero.
   *
   * <p><strong>The Umbuchung fee is borne by the KRT ({@code CARTEL}) account</strong>
   * (REQ-BANK-031, #998 — supersedes the fee-free ADR-0052/ADR-0039 clause). {@code fee =
   * round(amount × rate)}: the <strong>source</strong> holder's custody is reduced by the fee
   * ({@code -(amount + fee)}), the destination is credited the full {@code amount}, and the fee is
   * <strong>debited from the CARTEL account</strong> ({@code -fee}) — so the holder ledger nets to
   * {@code -fee} and the CARTEL account bears the real aUEC lost to the game. The CARTEL account is
   * locked + overdraft-guarded and is never driven negative; a <em>missing or closed</em> CARTEL
   * account rejects the Umbuchung ({@code BANK_ACCOUNT_CLOSED}), and a fee it cannot cover is a
   * {@code BANK_OVERDRAFT}. A tiny amount whose fee rounds to {@code 0} keeps the legacy fee-free
   * shape (no account leg, holder legs net to zero).
   *
   * @param request validated holder-transfer payload (source and destination holders must differ)
   * @return acknowledgement of the created transaction
   * @throws NotFoundException when a holder does not exist
   * @throws BankConflictException {@code BANK_SELF_TRANSFER} when source equals destination, {@code
   *     BANK_ACCOUNT_CLOSED} when the CARTEL account is missing/closed, or {@code BANK_OVERDRAFT}
   *     when the fee would overdraw it
   */
  @Transactional
  public BankTransactionDto bookHolderTransfer(@NotNull BankHolderTransferRequest request) {
    if (request.sourceHolderId().equals(request.destinationHolderId())) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_SELF_TRANSFER,
          "Source and destination holder of an Umbuchung must differ");
    }
    BankHolder sourceHolder = writer.requireHolder(request.sourceHolderId());
    BankHolder destinationHolder = writer.requireHolder(request.destinationHolderId());

    // The fee is borne by, and debited from, the KRT/CARTEL account (#998). Locked + overdraft-
    // guarded so it is never driven negative; missing/closed -> BANK_ACCOUNT_CLOSED. A fee that
    // rounds
    // to 0 (tiny amount) books no account leg and keeps the legacy fee-free shape.
    BigDecimal fee = transferFeeService.feeOn(request.amount());
    BankAccount cartel = null;
    if (fee.signum() > 0) {
      UUID cartelId =
          accountRepository
              .findFirstByType(BankAccountType.CARTEL)
              .map(BankAccount::getId)
              .orElseThrow(
                  () ->
                      new BankConflictException(
                          BankConflictException.CODE_BANK_ACCOUNT_CLOSED,
                          "The KRT (CARTEL) account that bears the Umbuchung fee does not exist"));
      cartel = writer.lockAccount(cartelId);
      guards.requireActive(cartel);
      guards.requireAccountCoverage(cartel, fee);
    }

    Instant now = Instant.now();
    BankTransaction tx =
        writer.persistTransaction(
            BankTransactionType.HOLDER_TRANSFER, request.note(), null, null, fee, now, null);
    writer.persistHolderPosting(tx, sourceHolder, request.amount().add(fee).negate(), now);
    writer.persistHolderPosting(tx, destinationHolder, request.amount(), now);
    if (cartel != null) {
      writer.persistAccountPosting(tx, cartel, fee.negate(), now);
    }
    bankAuditService.record(
        BankAuditEventType.HOLDER_TRANSFER,
        cartel == null ? null : cartel.getId(),
        tx.getId(),
        null,
        plain(request.amount())
            + " aUEC "
            + sourceHolder.getHandle()
            + " -> "
            + destinationHolder.getHandle()
            + feeDetail(fee, false));
    return toDto(tx);
  }

  /**
   * Reverses a transaction (REQ-BANK-004): books a {@code REVERSAL} whose legs are the negated
   * mirror of the original's legs on <strong>both</strong> ledgers (ADR-0039), referencing the
   * original. A transaction can be reversed at most once; a {@code WIPE_RESET} and a {@code
   * REVERSAL} itself are not reversible. Only the <strong>account</strong> legs are re-checked
   * against overdraft (undoing a deposit whose account has meanwhile been drained is rejected); the
   * holder dimension may go negative.
   *
   * @param transactionId the transaction to reverse
   * @param note optional correction note
   * @return acknowledgement of the created reversal
   * @throws NotFoundException when the transaction does not exist
   * @throws BankConflictException with {@code BANK_NOT_REVERSIBLE}, {@code BANK_ALREADY_REVERSED},
   *     {@code BANK_ACCOUNT_CLOSED} or {@code BANK_OVERDRAFT}
   */
  @Transactional
  public BankTransactionDto reverseTransaction(@NotNull UUID transactionId, @Nullable String note) {
    final BankTransaction original =
        transactionRepository
            .findById(transactionId)
            .orElseThrow(() -> new NotFoundException("Bank transaction not found"));
    if (original.getType() == BankTransactionType.WIPE_RESET
        || original.getType() == BankTransactionType.REVERSAL) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_NOT_REVERSIBLE,
          "Wipe resets and reversals cannot themselves be reversed",
          Map.of("transactionType", original.getType().name()));
    }
    if (transactionRepository.existsByReversedTransactionId(transactionId)) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_ALREADY_REVERSED,
          "The transaction has already been reversed");
    }
    List<BankCounterLeg> accountLegs =
        postingRepository.findLegsByTransactionIds(List.of(transactionId));
    final List<BankHolderLeg> holderLegs =
        holderPostingRepository.findHolderLegsByTransactionIds(List.of(transactionId));

    Map<UUID, BankAccount> lockedAccounts =
        accountLegs.stream()
            .map(BankCounterLeg::accountId)
            .distinct()
            .sorted()
            .collect(
                Collectors.toMap(id -> id, writer::lockAccount, (a, b) -> a, LinkedHashMap::new));
    lockedAccounts.values().forEach(guards::requireActive);

    // Validate the negated mirror against the current account balances: an account leg that was
    // positive becomes a removal and must still be covered (REQ-BANK-006). Holder legs are not
    // checked - the holder dimension may go negative (ADR-0039).
    for (BankCounterLeg leg : accountLegs) {
      BigDecimal negated = leg.amount().negate();
      if (negated.signum() < 0) {
        BankAccount account = lockedAccounts.get(leg.accountId());
        BigDecimal removal = negated.negate();
        BigDecimal balance = postingRepository.accountBalance(leg.accountId());
        if (balance.compareTo(removal) < 0) {
          throw guards.accountOverdraft(account.getAccountNo(), balance);
        }
      }
    }

    Instant now = Instant.now();
    // A reversal negates the original's actual recorded legs (source leg = the gross debited,
    // destination leg = the amount that arrived), so the pair cancels exactly per account/holder
    // and
    // the reversal itself carries no new fee (ADR-0052): the in-game money was already moved; this
    // is
    // a bookkeeping correction. Restoring the gross makes the source whole again.
    BankTransaction reversal =
        writer.persistTransaction(
            BankTransactionType.REVERSAL, note, null, original, BigDecimal.ZERO, now, null);
    for (BankCounterLeg leg : accountLegs) {
      writer.persistAccountPosting(
          reversal, lockedAccounts.get(leg.accountId()), leg.amount().negate(), now);
    }
    Map<UUID, BankHolder> reversalHolders =
        writer.loadHolders(holderLegs.stream().map(BankHolderLeg::holderId).toList());
    for (BankHolderLeg leg : holderLegs) {
      BankHolder holder = writer.requireHolder(reversalHolders, leg.holderId());
      writer.persistHolderPosting(reversal, holder, leg.amount().negate(), now);
    }
    bankAuditService.record(
        BankAuditEventType.TRANSACTION_REVERSED,
        accountLegs.isEmpty() ? null : accountLegs.getFirst().accountId(),
        reversal.getId(),
        null,
        "reversed " + original.getType() + " " + shortId(original.getId()));
    return toDto(reversal);
  }

  /**
   * Executes the admin wipe reset (REQ-BANK-013, ADR-0039): one {@code WIPE_RESET} transaction that
   * books a negative account leg for every account with a non-zero balance <strong>and</strong> a
   * negative holder leg for every holder with a non-zero global balance, zeroing both dimensions
   * independently. History, statements and audit trail are preserved — nothing is deleted.
   * Idempotent: on an all-zero bank nothing is booked and the result reports zero.
   *
   * @return counts and total for the admin notice; one summarizing audit event is written when
   *     anything was zeroed
   */
  @Transactional
  public BankWipeResetResultDto resetAllBalances() {
    List<BankAccount> accounts = accountRepository.findAllForUpdateOrderById();
    Map<UUID, BigDecimal> accountBalances =
        accounts.stream()
            .collect(
                Collectors.toMap(
                    BankAccount::getId, a -> postingRepository.accountBalance(a.getId())));
    // The holder dimension is lock-free by design (no overdraft invariant to protect, ADR-0039), so
    // the holder zeroing is NOT serialized against a concurrent bookHolderTransfer. This is only
    // reachable via the admin post-SC-wipe operation, which runs on a quiescent bank; any residual
    // a
    // racing Umbuchung might leave is reconcilable by a follow-up Umbuchung. Account legs ARE
    // serialized (the accounts are locked above).
    List<BankHolderBalance> holderBalances =
        holderPostingRepository.holderTotals().stream()
            .filter(h -> h.amount().signum() != 0)
            .toList();

    int accountsReset =
        (int) accountBalances.values().stream().filter(b -> b.signum() != 0).count();
    int stashesZeroed = holderBalances.size();
    if (accountsReset == 0 && stashesZeroed == 0) {
      log.info("Bank wipe reset executed: nothing to zero (idempotent no-op).");
      return new BankWipeResetResultDto(0, 0, BigDecimal.ZERO);
    }

    Instant now = Instant.now();
    BankTransaction tx =
        writer.persistTransaction(
            BankTransactionType.WIPE_RESET,
            "SC wipe reset",
            null,
            null,
            BigDecimal.ZERO,
            now,
            null);
    BigDecimal totalZeroed = BigDecimal.ZERO;
    for (BankAccount account : accounts) {
      BigDecimal balance = accountBalances.get(account.getId());
      if (balance.signum() != 0) {
        writer.persistAccountPosting(tx, account, balance.negate(), now);
        totalZeroed = totalZeroed.add(balance);
      }
    }
    Map<UUID, BankHolder> holders =
        writer.loadHolders(holderBalances.stream().map(BankHolderBalance::holderId).toList());
    for (BankHolderBalance slice : holderBalances) {
      BankHolder holder = writer.requireHolder(holders, slice.holderId());
      writer.persistHolderPosting(tx, holder, slice.amount().negate(), now);
    }

    bankAuditService.record(
        BankAuditEventType.WIPE_RESET_EXECUTED,
        null,
        tx.getId(),
        null,
        "accounts="
            + accountsReset
            + ", stashes="
            + stashesZeroed
            + ", totalZeroed="
            + totalZeroed.toPlainString());
    log.info(
        "Bank wipe reset executed: accounts={}, stashes={}, totalZeroed={}",
        accountsReset,
        stashesZeroed,
        totalZeroed);
    return new BankWipeResetResultDto(accountsReset, stashesZeroed, totalZeroed);
  }

  /**
   * Resolves the optional deposit/withdrawal counterparty (REQ-BANK-044, #994) into a snapshot
   * stamped on the transaction header. The counterparty is either a <strong>registered</strong>
   * tool user or an <strong>external</strong> free-text name (mutually exclusive; supplying both is
   * a 400). With neither, no counterparty is recorded (a lone org unit is a 400).
   *
   * <ul>
   *   <li><strong>Registered</strong> ({@code userId}): the handle is snapshotted from the user's
   *       effective name; a named org unit is validated to be one of the user's own memberships
   *       across all four kinds (via the shared, kind-safe {@link
   *       OrgUnitMembershipService#listDirectMembershipOptions}) and its name snapshotted.
   *   <li><strong>External</strong> ({@code externalName}, #994): the handle is snapshotted from
   *       the free-text name and <em>no</em> {@code counterparty_user_id} FK is stored; a named org
   *       unit may be <em>any</em> active org unit (the membership check is skipped, since there is
   *       no linked user), resolved to its name snapshot via {@link
   *       OrgUnitMembershipService#listAllActiveOrgUnitOptionsAllKinds}.
   * </ul>
   *
   * <p>Either way the org-unit name is snapshotted so a later user/org-unit deletion leaves the
   * recorded booking intact.
   *
   * @param userId the registered counterparty user id, or {@code null}
   * @param externalName the external free-text counterparty name (#994), or {@code null}/blank
   * @param orgUnitId the counterparty's chosen org unit, or {@code null}
   * @return the resolved snapshot, or {@code null} when no counterparty was chosen
   * @throws BadRequestException when both a user and an external name are given, when an org unit
   *     is named without a counterparty, when a registered user's org unit is not one of their
   *     memberships, or when an external counterparty's org unit does not exist / is inactive
   * @throws NotFoundException when the named registered counterparty user does not exist
   */
  @Nullable
  private CounterpartySnapshot resolveCounterparty(
      @Nullable UUID userId, @Nullable String externalName, @Nullable UUID orgUnitId) {
    String external = externalName == null || externalName.isBlank() ? null : externalName.trim();
    if (userId != null && external != null) {
      throw new BadRequestException(
          "A counterparty is either a registered user or an external free-text name, not both");
    }
    if (userId == null && external == null) {
      if (orgUnitId != null) {
        throw new BadRequestException("A counterparty org unit requires a counterparty");
      }
      return null;
    }
    if (userId != null) {
      User user =
          userRepository
              .findById(userId)
              .orElseThrow(() -> new NotFoundException("Counterparty user not found"));
      if (orgUnitId == null) {
        return new CounterpartySnapshot(user.getId(), user.getEffectiveName(), null, null);
      }
      OrgUnitMembershipOptionDto membership =
          orgUnitMembershipQueryService.listDirectMembershipOptions(userId).stream()
              .filter(option -> option.orgUnitId().equals(orgUnitId))
              .findFirst()
              .orElseThrow(
                  () ->
                      new BadRequestException(
                          "The selected org unit is not one of the counterparty's memberships"));
      return new CounterpartySnapshot(
          user.getId(), user.getEffectiveName(), membership.orgUnitId(), membership.orgUnitName());
    }
    // External counterparty (#994): free-text name + any active org unit, no membership check.
    if (orgUnitId == null) {
      return new CounterpartySnapshot(null, external, null, null);
    }
    OrgUnitMembershipOptionDto orgUnit =
        orgUnitMembershipQueryService.listAllActiveOrgUnitOptionsAllKinds().stream()
            .filter(option -> option.orgUnitId().equals(orgUnitId))
            .findFirst()
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "The selected counterparty org unit does not exist or is inactive"));
    return new CounterpartySnapshot(null, external, orgUnit.orgUnitId(), orgUnit.orgUnitName());
  }

  /**
   * Renders the audit-detail suffix naming the deposit/withdrawal counterparty (REQ-BANK-044):
   * {@code " <- handle (OrgUnit)"} for a deposit (money came FROM the Einzahler) or {@code " ->
   * handle (OrgUnit)"} for a withdrawal (money went TO the Empf&auml;nger); empty when no
   * counterparty was recorded. The org-unit segment is omitted when none was chosen. Only the
   * handle and org-unit name — both system identifiers, not user free text — appear, consistent
   * with the existing holder-handle detail.
   *
   * @param counterparty the resolved counterparty, or {@code null}
   * @param arrow the direction marker ({@code "<-"} deposit, {@code "->"} withdrawal)
   * @return the counterparty suffix, or an empty string when there is none
   */
  private static String counterpartyDetail(
      @Nullable CounterpartySnapshot counterparty, @NotNull String arrow) {
    if (counterparty == null) {
      return "";
    }
    String orgUnit =
        counterparty.orgUnitName() == null ? "" : " (" + counterparty.orgUnitName() + ")";
    return " " + arrow + " " + counterparty.handle() + orgUnit;
  }

  /**
   * Renders the audit-detail suffix for a fee-bearing transaction (ADR-0052, REQ-BANK-033): {@code
   * " (fee N aUEC)"} — or {@code " (fee N aUEC, incl)"} in the fee-inclusive mode (#999) so the
   * mode is auditable — when the fee is positive, empty otherwise. No PII — only the numeric fee
   * and the mode marker.
   *
   * @param fee the transfer fee (round(entered x rate)) recorded on the transaction
   * @param feeInclusive whether the entered amount was the gross debited (inclusive) rather than
   *     the amount that arrives (on-top); only distinguishes when the fee is positive
   * @return the fee suffix, or an empty string when there is no fee
   */
  private static String feeDetail(@NotNull BigDecimal fee, boolean feeInclusive) {
    if (fee.signum() <= 0) {
      return "";
    }
    return " (fee " + plain(fee) + " aUEC" + (feeInclusive ? ", incl" : "") + ")";
  }

  /**
   * Maps a persisted header to the acknowledgement DTO.
   *
   * @param tx the persisted header
   * @return the acknowledgement
   */
  private static BankTransactionDto toDto(@NotNull BankTransaction tx) {
    return new BankTransactionDto(tx.getId(), tx.getType(), tx.getNote(), tx.getCreatedAt());
  }

  /**
   * Shortens a transaction id to the first hex group for compact audit details (the A2 mockup's "TX
   * a4f1" style).
   *
   * @param id the transaction id
   * @return the first id segment
   */
  private static String shortId(@NotNull UUID id) {
    String s = id.toString();
    int dash = s.indexOf('-');
    return dash < 0 ? s : s.substring(0, dash);
  }
}
