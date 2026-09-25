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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
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
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
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
 * The bank's booking engine: books deposits, withdrawals, transfers, holder Umbuchungen, reversals
 * and the wipe reset onto the account ledger ({@code bank_posting}) and the holder ledger ({@code
 * bank_holder_posting}) (REQ-BANK-004, ADR-0039).
 *
 * <p>Persistence is delegated to {@link BankPostingWriter} and validation to {@link
 * BankBookingGuards}. Bookings lock the affected account rows in ascending id order before reading
 * balances, so the account no-overdraft rule (REQ-BANK-006) cannot be raced; holder balances may go
 * negative. Every booking appends one audit row via {@link BankAuditService} in the same
 * transaction.
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
  @NotNull
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
    BankTransaction tx =
        writer.persistTransaction(
            BankTransactionType.DEPOSIT,
            request.note(),
            null,
            request.staffNote(),
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
   * Books a split deposit (REQ-BANK-043): one {@code DEPOSIT} with a single holder leg for the
   * gross and account legs distributing {@code round(gross × percent / 100)} evenly over the other
   * active squadron accounts, the named account receiving the remainder.
   *
   * <p>All affected accounts are locked in ascending id order; zero-amount legs are dropped and
   * squadron accounts closed in the meantime are skipped.
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
  @NotNull
  private BankTransactionDto bookSplitDeposit(
      @NotNull BankDepositRequest request, @NotNull BankHolder holder) {
    BigDecimal gross = request.amount();
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
            request.staffNote(),
            null,
            BigDecimal.ZERO,
            now,
            counterparty);
    if (namedShare.signum() > 0) {
      writer.persistAccountPosting(tx, named, namedShare, now);
    }
    shares.forEach((id, share) -> writer.persistAccountPosting(tx, locked.get(id), share, now));
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
   * Books a withdrawal (REQ-BANK-004): one negative account leg and one negative holder leg,
   * guarded against overdraft at account level only (REQ-BANK-006).
   *
   * <p>By default the in-game fee is added on top of the entered amount; in fee-inclusive mode the
   * entered amount is the gross debited (REQ-BANK-033). An optional counterparty (Empf&auml;nger)
   * is recorded on the transaction header (REQ-BANK-044).
   *
   * @param request validated withdrawal payload
   * @return acknowledgement of the created transaction
   * @throws NotFoundException when account, holder or the named counterparty user do not exist
   * @throws BankConflictException with {@code BANK_ACCOUNT_CLOSED} or {@code BANK_OVERDRAFT}
   * @throws BadRequestException when a counterparty org unit is named that is not one of the
   *     counterparty user's memberships (REQ-BANK-044)
   */
  @NotNull
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
            request.staffNote(),
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
   * Books an account-to-account transfer (REQ-BANK-011): two account legs and two holder legs. The
   * source account is guarded against overdraft, and the destination must be visible to the caller.
   *
   * <p>When custody changes hands the in-game fee applies, on top by default or included in
   * fee-inclusive mode (REQ-BANK-033); a same-holder transfer is fee-free. The account legs always
   * net to {@code -fee}.
   *
   * @param request validated transfer payload (source and destination accounts must differ)
   * @param destinationVisible whether the caller may see the destination account (from {@code
   *     BankSecurityService.canSee})
   * @return acknowledgement of the created transaction
   * @throws NotFoundException when an account or holder does not exist
   * @throws AccessDeniedException when the destination is not visible to the caller
   * @throws BankConflictException with {@code BANK_SELF_TRANSFER}, {@code BANK_ACCOUNT_CLOSED},
   *     {@code BANK_HOLDER_INACTIVE} or {@code BANK_OVERDRAFT}
   */
  @NotNull
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

    final boolean holderChanges = !sourceHolder.getId().equals(destinationHolder.getId());
    BigDecimal fee = holderChanges ? transferFeeService.feeOn(request.amount()) : BigDecimal.ZERO;
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
            request.staffNote(),
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
   * Books a holder-to-holder Umbuchung (REQ-BANK-031, ADR-0039): two holder legs and no account
   * leg, ignoring the holders' active flags.
   *
   * <p>A non-zero fee {@code round(amount × rate)} reduces the source holder by {@code amount +
   * fee} and is debited from the KRT ({@code CARTEL}) account, which is locked and
   * overdraft-guarded.
   *
   * @param request validated holder-transfer payload (source and destination holders must differ)
   * @return acknowledgement of the created transaction
   * @throws NotFoundException when a holder does not exist
   * @throws BankConflictException {@code BANK_SELF_TRANSFER} when source equals destination, {@code
   *     BANK_ACCOUNT_CLOSED} when the CARTEL account is missing/closed, or {@code BANK_OVERDRAFT}
   *     when the fee would overdraw it
   */
  @NotNull
  @Transactional
  public BankTransactionDto bookHolderTransfer(@NotNull BankHolderTransferRequest request) {
    if (request.sourceHolderId().equals(request.destinationHolderId())) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_SELF_TRANSFER,
          "Source and destination holder of an Umbuchung must differ");
    }
    BankHolder sourceHolder = writer.requireHolder(request.sourceHolderId());
    BankHolder destinationHolder = writer.requireHolder(request.destinationHolderId());

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
            BankTransactionType.HOLDER_TRANSFER, request.note(), null, null, null, fee, now, null);
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
  @NotNull
  @Transactional
  public BankTransactionDto reverseTransaction(@NotNull UUID transactionId, @Nullable String note) {
    final BankTransaction original =
        Entities.require(
            transactionRepository.findById(transactionId), "Bank transaction not found");
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
    BankTransaction reversal =
        writer.persistTransaction(
            BankTransactionType.REVERSAL, note, null, null, original, BigDecimal.ZERO, now, null);
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
  @NotNull
  @Transactional
  public BankWipeResetResultDto resetAllBalances() {
    List<BankAccount> accounts = accountRepository.findAllForUpdateOrderById();
    Map<UUID, BigDecimal> accountBalances =
        accounts.stream()
            .collect(
                Collectors.toMap(
                    BankAccount::getId, a -> postingRepository.accountBalance(a.getId())));
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
   * Resolves the optional deposit/withdrawal counterparty (REQ-BANK-044) into a header snapshot:
   * either a registered user, whose org unit must be one of their memberships, or an external
   * free-text name, whose org unit may be any active one.
   *
   * @param userId the registered counterparty user id, or {@code null}
   * @param externalName the external free-text counterparty name, or {@code null}/blank
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
    String external = StringNormalization.trimToNull(externalName);
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
      User user = Entities.require(userRepository.findById(userId), "Counterparty user not found");
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
   * Renders the audit-detail suffix naming the counterparty (REQ-BANK-044): {@code " <- handle
   * (OrgUnit)"} for a deposit or {@code " -> handle (OrgUnit)"} for a withdrawal, omitting the org
   * unit when none was chosen.
   *
   * @param counterparty the resolved counterparty, or {@code null}
   * @param arrow the direction marker ({@code "<-"} deposit, {@code "->"} withdrawal)
   * @return the counterparty suffix, or an empty string when there is none
   */
  @NotNull
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
   * Renders the audit-detail fee suffix (REQ-BANK-033): {@code " (fee N aUEC)"}, or {@code " (fee N
   * aUEC, incl)"} in fee-inclusive mode, when the fee is positive.
   *
   * @param fee the transfer fee recorded on the transaction
   * @param feeInclusive whether the entered amount was the gross debited rather than the amount
   *     that arrives
   * @return the fee suffix, or an empty string when there is no fee
   */
  @NotNull
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
  @NotNull
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
