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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.events.BankNotices;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank's low-level persistence engine: account row locks, holder resolution and the insert-only
 * writes of transaction headers and signed account and holder legs (REQ-BANK-004, ADR-0039).
 *
 * <p>Every method is {@link Propagation#MANDATORY} and must run inside a booking transaction opened
 * by {@link BankLedgerService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class BankPostingWriter {

  /** PostgreSQL compares the UUID bytes as unsigned. */
  static final Comparator<UUID> ACCOUNT_ID_ORDER =
      (left, right) -> {
        int high =
            Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high == 0
            ? Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits())
            : high;
      };

  private final BankAccountRepository accountRepository;
  private final BankHolderRepository holderRepository;
  private final BankTransactionRepository transactionRepository;
  private final BankPostingRepository postingRepository;
  private final BankHolderPostingRepository holderPostingRepository;
  private final AuthHelperService authHelperService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Locks one account row for the surrounding transaction (the serialization point of every account
   * booking).
   *
   * @param accountId the account to lock
   * @return the locked, managed account
   * @throws NotFoundException when the account does not exist
   */
  public BankAccount lockAccount(@NotNull UUID accountId) {
    return Entities.require(
        accountRepository.findByIdForUpdate(accountId), "Bank account not found");
  }

  /**
   * Locks every account and then every holder, each in id order, for a wipe reset that reads and
   * offsets the whole ledger (REQ-BANK-013, REQ-PATCH-041): every booking, a custody move without an
   * account leg included, waits until the reset ends.
   *
   * @return every account, locked, in id order
   */
  public List<BankAccount> lockLedger() {
    List<BankAccount> accounts = accountRepository.findAllForUpdateOrderById();
    holderRepository.findAllForUpdateOrderById();
    return accounts;
  }

  /**
   * Locks the holders a booking moves custody between, in id order and after the booking's account
   * locks, so the booking and a wipe reset take turns (REQ-BANK-031, REQ-PATCH-041).
   *
   * @param holderIds the holders the booking posts to; may be empty
   * @return holder id to locked entity for every id that exists
   */
  public Map<UUID, BankHolder> lockHolders(@NotNull Collection<UUID> holderIds) {
    if (holderIds.isEmpty()) {
      return Map.of();
    }
    return holderRepository.findAllByIdForUpdateOrderById(Set.copyOf(holderIds)).stream()
        .collect(Collectors.toMap(BankHolder::getId, holder -> holder));
  }

  /**
   * Resolves a holder or fails with 404.
   *
   * @param holderId the holder id
   * @return the holder entity
   * @throws NotFoundException when the holder does not exist
   */
  public BankHolder requireHolder(@NotNull UUID holderId) {
    return Entities.require(holderRepository.findById(holderId), "Bank holder not found");
  }

  /**
   * Resolves a holder from a map pre-loaded by {@link #loadHolders(Collection)} or {@link
   * #lockHolders(Collection)}.
   *
   * @param holders the pre-loaded holder map
   * @param holderId the holder to resolve
   * @return the managed holder
   * @throws NotFoundException when no holder with that id exists
   */
  public BankHolder requireHolder(@NotNull Map<UUID, BankHolder> holders, @NotNull UUID holderId) {
    BankHolder holder = holders.get(holderId);
    if (holder == null) {
      throw new NotFoundException("Bank holder not found");
    }
    return holder;
  }

  /**
   * Loads the given holders in one query, keyed by id, for batch posting loops (REQ-DATA-003).
   *
   * @param holderIds the holder ids to load; may be empty
   * @return holder id to entity for every id that exists
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
   * @param justification the Begr&uuml;ndung, set only on a {@code WITHDRAWAL} or {@code TRANSFER}
   *     (REQ-BANK-045), else {@code null}
   * @param staffNote the bank employee's note (REQ-BANK-054), or {@code null}
   * @param reversed the reversed original for {@code REVERSAL} rows, else {@code null}
   * @param fee the in-game transfer fee added on top (ADR-0052); {@link BigDecimal#ZERO} when none
   * @param now the shared booking instant
   * @param counterparty the deposit/withdrawal counterparty (REQ-BANK-044), or {@code null}
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
        header(type, note, now)
            .justification(justification)
            .staffNote(staffNote)
            .reversedTransaction(reversed)
            .transferFee(fee)
            .counterpartyUserId(counterparty == null ? null : counterparty.userId())
            .counterpartyHandle(counterparty == null ? null : counterparty.handle())
            .counterpartyOrgUnitId(counterparty == null ? null : counterparty.orgUnitId())
            .counterpartyOrgUnitName(counterparty == null ? null : counterparty.orgUnitName())
            .build();
    return transactionRepository.save(tx);
  }

  /**
   * Persists one {@code WIPE_RESET} header stamped with the caller and the shared booking instant.
   *
   * @param note the reset's note
   * @param now the shared booking instant
   * @param effectiveAt the live time of the patch change whose currency wipe books the reset, or
   *     {@code null} for the standalone reset, which takes effect when it is recorded
   * @return the persisted header
   */
  public BankTransaction persistWipeReset(
      @NotNull String note, @NotNull Instant now, @Nullable Instant effectiveAt) {
    return transactionRepository.save(
        header(BankTransactionType.WIPE_RESET, note, now).effectiveAt(effectiveAt).build());
  }

  /**
   * Starts a transaction header with the fields every booking carries.
   *
   * @param type the transaction type
   * @param note optional free-text note
   * @param now the shared booking instant
   * @return the builder, to be completed by the caller
   */
  private BankTransaction.BankTransactionBuilder header(
      @NotNull BankTransactionType type, @Nullable String note, @NotNull Instant now) {
    return BankTransaction.builder()
        .type(type)
        .initiatedBy(authHelperService.currentUserId().orElse(null))
        .note(note)
        .createdAt(now);
  }

  /**
   * Persists one signed account leg stamped with the shared booking instant.
   *
   * @param tx the owning header
   * @param account the posted account
   * @param amount the signed, non-zero amount
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
   * @param amount the signed, non-zero amount
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
    if (!holder.isActive() && holderPostingRepository.holderTotal(holder.getId()).signum() == 0) {
      eventPublisher.publishEvent(BankNotices.holderNoticeCleared(holder.getId()));
    }
  }
}
