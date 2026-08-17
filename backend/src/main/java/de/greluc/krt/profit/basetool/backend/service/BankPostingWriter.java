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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.BankHolderPosting;
import de.greluc.krt.profit.basetool.backend.model.BankPosting;
import de.greluc.krt.profit.basetool.backend.model.BankTransaction;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank's low-level persistence engine (extracted from {@link BankLedgerService}, #1253): the
 * mechanical row-locking, entity resolution and append-only writes that every booking path composes
 * — locking an account row for update, resolving/pre-loading holders, and inserting the transaction
 * header plus its signed account and holder legs onto the <strong>two</strong> ledgers ({@code
 * bank_posting} / {@code bank_holder_posting}, REQ-BANK-004, ADR-0010/0039). The booking
 * orchestration, fee arithmetic, audit trail and every validation guard stay in {@link
 * BankLedgerService} / {@link BankBookingGuards}; this class only touches the database.
 *
 * <p><strong>Every method is {@link Propagation#MANDATORY}</strong> — it may only run inside an
 * already-open booking transaction driven by a {@code BankLedgerService} entry point. That is what
 * makes the row lock ({@link #lockAccount}) serialize value movement against concurrent bookings
 * and keeps the ledger inserts atomic with the surrounding audit write; calling any of these
 * outside a transaction is a programming error and fails fast rather than silently committing a
 * partial booking. The ledger rows are insert-only — no {@code @Version} churn, no {@code
 * save()}-on-managed traps.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class BankPostingWriter {

  private final BankAccountRepository accountRepository;
  private final BankHolderRepository holderRepository;
  private final BankTransactionRepository transactionRepository;
  private final BankPostingRepository postingRepository;
  private final BankHolderPostingRepository holderPostingRepository;
  private final AuthHelperService authHelperService;

  /**
   * Locks one account row for the surrounding transaction (the serialization point of every account
   * booking).
   *
   * @param accountId the account to lock
   * @return the locked, managed account
   * @throws NotFoundException when the account does not exist
   */
  public BankAccount lockAccount(@NotNull UUID accountId) {
    return accountRepository
        .findByIdForUpdate(accountId)
        .orElseThrow(() -> new NotFoundException("Bank account not found"));
  }

  /**
   * Resolves a holder or fails with 404.
   *
   * @param holderId the holder id
   * @return the holder entity
   * @throws NotFoundException when the holder does not exist
   */
  public BankHolder requireHolder(@NotNull UUID holderId) {
    return holderRepository
        .findById(holderId)
        .orElseThrow(() -> new NotFoundException("Bank holder not found"));
  }

  /**
   * Resolves a holder from a pre-loaded batch ({@link #loadHolders(Collection)}), enforcing the
   * same not-found contract as {@link #requireHolder(UUID)}.
   *
   * @param holders the pre-loaded holder map.
   * @param holderId the holder to resolve.
   * @return the managed holder.
   * @throws NotFoundException when no holder with that id exists.
   */
  public BankHolder requireHolder(@NotNull Map<UUID, BankHolder> holders, @NotNull UUID holderId) {
    BankHolder holder = holders.get(holderId);
    if (holder == null) {
      throw new NotFoundException("Bank holder not found");
    }
    return holder;
  }

  /**
   * Pre-loads the holders referenced by a batch posting loop in one query, keyed by id, so the loop
   * (wipe-reset / reversal) does not fire {@link #requireHolder(UUID)}'s {@code findById} per
   * leg/slice (REQ-DATA-003).
   *
   * @param holderIds the holder ids to load; may be empty.
   * @return holder id → entity for every id that exists.
   */
  public Map<UUID, BankHolder> loadHolders(@NotNull Collection<UUID> holderIds) {
    return holderRepository.findAllById(holderIds).stream()
        .collect(Collectors.toMap(BankHolder::getId, holder -> holder));
  }

  /**
   * Persists one transaction header stamped with the caller and the shared booking instant.
   *
   * @param type the transaction type
   * @param note optional free-text note
   * @param justification optional free-text justification (Begr&uuml;ndung), only a {@code
   *     WITHDRAWAL} / {@code TRANSFER} carries one (REQ-BANK-045); {@code null} otherwise
   * @param staffNote optional free-text note authored by the booking bank employee ("Notiz
   *     Bankmitarbeiter", REQ-BANK-054); carried by every employee-initiated kind incl. a {@code
   *     DEPOSIT}, and {@code null} for the holder Umbuchung, reversals and the wipe reset
   * @param reversed the reversed original for {@code REVERSAL} rows, else {@code null}
   * @param fee the in-game transfer fee added on top of the entered amount (ADR-0052); {@link
   *     BigDecimal#ZERO} for non-fee transactions
   * @param now the shared booking instant
   * @param counterparty the deposit/withdrawal counterparty to stamp on the header (REQ-BANK-044),
   *     or {@code null} for transfers, holder→holder Umbuchungen, reversals, the wipe reset and
   *     bookings without a recorded counterparty
   * @return the persisted header
   */
  public BankTransaction persistTransaction(
      @NotNull BankTransactionType type,
      @Nullable String note,
      @Nullable String justification,
      @Nullable String staffNote,
      @Nullable BankTransaction reversed,
      @NotNull BigDecimal fee,
      @NotNull Instant now,
      @Nullable CounterpartySnapshot counterparty) {
    BankTransaction tx =
        BankTransaction.builder()
            .type(type)
            .initiatedBy(authHelperService.currentUserId().orElse(null))
            .note(note)
            .justification(justification)
            .staffNote(staffNote)
            .reversedTransaction(reversed)
            .transferFee(fee)
            .counterpartyUserId(counterparty == null ? null : counterparty.userId())
            .counterpartyHandle(counterparty == null ? null : counterparty.handle())
            .counterpartyOrgUnitId(counterparty == null ? null : counterparty.orgUnitId())
            .counterpartyOrgUnitName(counterparty == null ? null : counterparty.orgUnitName())
            .createdAt(now)
            .build();
    return transactionRepository.save(tx);
  }

  /**
   * Persists one signed account leg stamped with the shared booking instant.
   *
   * @param tx the owning header
   * @param account the posted account
   * @param amount the signed amount (never zero — callers always pass validated non-zero values)
   * @param now the shared booking instant
   */
  public void persistAccountPosting(
      @NotNull BankTransaction tx,
      @NotNull BankAccount account,
      @NotNull BigDecimal amount,
      @NotNull Instant now) {
    BankPosting posting =
        BankPosting.builder()
            .transaction(tx)
            .account(account)
            .amount(amount)
            .createdAt(now)
            .build();
    postingRepository.save(posting);
  }

  /**
   * Persists one signed holder leg stamped with the shared booking instant.
   *
   * @param tx the owning header
   * @param holder the named holder
   * @param amount the signed amount (never zero — callers always pass validated non-zero values)
   * @param now the shared booking instant
   */
  public void persistHolderPosting(
      @NotNull BankTransaction tx,
      @NotNull BankHolder holder,
      @NotNull BigDecimal amount,
      @NotNull Instant now) {
    BankHolderPosting posting =
        BankHolderPosting.builder()
            .transaction(tx)
            .holder(holder)
            .amount(amount)
            .createdAt(now)
            .build();
    holderPostingRepository.save(posting);
  }
}
