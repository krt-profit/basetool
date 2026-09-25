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

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.BankHolderMapper;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankHolderBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RegisterBankHolderRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankHolderRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBookingRow;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank-local holder registry (REQ-BANK-003): registration with a deletion-proof handle
 * snapshot, activity toggling, and a listing with custody totals. Holders are never hard-deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BankHolderService {

  private final BankHolderRepository holderRepository;
  private final BankHolderPostingRepository holderPostingRepository;
  private final BankPostingRepository postingRepository;
  private final UserRepository userRepository;
  private final BankHolderMapper bankHolderMapper;
  private final BankAuditService bankAuditService;

  /**
   * Lists all holders with their global custody totals from one grouped query (ADR-0039), sorted
   * case-insensitively by the live display name (REQ-BANK-003).
   *
   * @return every holder row as DTO, ordered case-insensitively by the shown display name
   */
  public List<BankHolderDto> getHolders() {
    Map<UUID, BigDecimal> totals =
        holderPostingRepository.holderTotals().stream()
            .collect(Collectors.toMap(BankHolderBalance::holderId, BankHolderBalance::amount));
    return holderRepository.findAllWithUser().stream()
        .map(
            holder ->
                bankHolderMapper.toDto(
                    holder, totals.getOrDefault(holder.getId(), BigDecimal.ZERO)))
        .sorted(Comparator.comparing(BankHolderDto::handle, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * Loads one holder row with its global custody total (REQ-BANK-032) — the header of the holder
   * detail page. Visibility is gated at the controller via {@code
   * BankSecurityService.canSeeHolder}; an employee reaches only their own holder, management any.
   *
   * @param holderId the holder
   * @return the holder DTO incl. its global custody total
   * @throws NotFoundException when the holder does not exist
   */
  public BankHolderDto getHolder(@NotNull UUID holderId) {
    BankHolder holder = requireHolder(holderId);
    return bankHolderMapper.toDto(holder, holderPostingRepository.holderTotal(holderId));
  }

  /**
   * Pages over one holder's custody history (REQ-BANK-032, ADR-0039), resolving each leg's account
   * and, for a {@code HOLDER_TRANSFER}, the counter holder in batched queries.
   *
   * @param holderId the holder
   * @param pageable page, size and whitelisted sort (default newest first)
   * @return one page of the holder's booking rows
   * @throws NotFoundException when the holder does not exist
   */
  public Page<BankHolderBookingDto> getHolderBookings(
      @NotNull UUID holderId, @NotNull Pageable pageable) {
    requireHolder(holderId);
    Page<BankHolderBookingRow> rows =
        holderPostingRepository.findHolderBookings(holderId, pageable);
    List<UUID> txIds =
        rows.getContent().stream().map(BankHolderBookingRow::transactionId).distinct().toList();
    Map<UUID, List<BankCounterLeg>> accountLegsByTx =
        txIds.isEmpty()
            ? Map.of()
            : postingRepository.findLegsByTransactionIds(txIds).stream()
                .collect(Collectors.groupingBy(BankCounterLeg::transactionId));
    Map<UUID, List<BankHolderLeg>> holderLegsByTx =
        txIds.isEmpty()
            ? Map.of()
            : holderPostingRepository.findHolderLegsByTransactionIds(txIds).stream()
                .collect(Collectors.groupingBy(BankHolderLeg::transactionId));
    return rows.map(row -> toHolderBookingDto(row, accountLegsByTx, holderLegsByTx));
  }

  /**
   * Maps one holder booking row to the DTO, resolving its context from the batched legs: for a
   * {@code HOLDER_TRANSFER} the counter holder (the opposite-sign holder leg), for every other
   * account-touching type the account this leg moved on (the account leg whose amount sign matches
   * this holder leg). A {@code WIPE_RESET} leg has no 1:1 account pairing and shows neither
   * (ADR-0039).
   *
   * @param row the projected holder booking row
   * @param accountLegsByTx account legs of the page's transactions, grouped by transaction
   * @param holderLegsByTx holder legs of the page's transactions, grouped by transaction
   * @return the holder booking DTO with its account/holder context resolved
   */
  @NotNull
  private BankHolderBookingDto toHolderBookingDto(
      @NotNull BankHolderBookingRow row,
      @NotNull Map<UUID, List<BankCounterLeg>> accountLegsByTx,
      @NotNull Map<UUID, List<BankHolderLeg>> holderLegsByTx) {
    int sign = row.amount().signum();
    String counterAccountNo = null;
    String counterAccountName = null;
    String counterHolderHandle = null;
    if (row.type() == BankTransactionType.HOLDER_TRANSFER) {
      counterHolderHandle =
          matchHolderHandle(holderLegsByTx.getOrDefault(row.transactionId(), List.of()), -sign);
    } else if (row.type() != BankTransactionType.WIPE_RESET) {
      BankCounterLeg account =
          matchAccountLeg(accountLegsByTx.getOrDefault(row.transactionId(), List.of()), sign);
      if (account != null) {
        counterAccountNo = account.accountNo();
        counterAccountName = account.accountName();
      }
    }
    return new BankHolderBookingDto(
        row.postingId(),
        row.transactionId(),
        row.type(),
        row.amount(),
        row.note(),
        row.createdAt(),
        row.reversedTransactionId(),
        counterAccountNo,
        counterAccountName,
        counterHolderHandle,
        row.transferFee());
  }

  /**
   * Picks the account leg whose amount sign matches the requested sign — the account paired with a
   * holder leg of the same sign in the same transaction (ADR-0039: deposit/withdrawal have the one
   * leg, a transfer the leg on the holder's side). Returns {@code null} when none matches.
   *
   * @param accountLegs the transaction's account legs
   * @param sign the wanted amount sign (+1, -1)
   * @return the matching account leg, or {@code null}
   */
  private static BankCounterLeg matchAccountLeg(
      @NotNull List<BankCounterLeg> accountLegs, int sign) {
    return accountLegs.stream()
        .filter(leg -> leg.amount().signum() == sign)
        .findFirst()
        .orElse(null);
  }

  /**
   * Picks the handle of the holder leg whose amount sign matches the requested sign — the counter
   * holder of a {@code HOLDER_TRANSFER} (the opposite-sign leg of the pair). Returns {@code null}
   * when none matches.
   *
   * @param holderLegs the transaction's holder legs
   * @param sign the wanted amount sign (+1, -1)
   * @return the matching holder's handle, or {@code null}
   */
  private static String matchHolderHandle(@NotNull List<BankHolderLeg> holderLegs, int sign) {
    return holderLegs.stream()
        .filter(leg -> leg.amount().signum() == sign)
        .map(BankHolderLeg::handle)
        .findFirst()
        .orElse(null);
  }

  /**
   * Loads a holder or fails with 404.
   *
   * @param holderId the holder id
   * @return the holder entity
   */
  private BankHolder requireHolder(@NotNull UUID holderId) {
    return Entities.require(holderRepository.findById(holderId), "Bank holder not found");
  }

  /**
   * Registers a basetool user as holder (REQ-BANK-003), snapshotting the effective name as the
   * deletion-proof handle. One holder row per user.
   *
   * @param request the user to register
   * @return the created holder row
   * @throws NotFoundException when the user does not exist
   * @throws DuplicateEntityException when the user is already registered
   */
  @Transactional
  public BankHolderDto registerHolder(@NotNull RegisterBankHolderRequest request) {
    User user = Entities.require(userRepository.findById(request.userId()), "User not found");
    if (holderRepository.existsByUserId(user.getId())) {
      throw new DuplicateEntityException("The user is already registered as a bank holder");
    }
    BankHolder holder = new BankHolder();
    holder.setUser(user);
    holder.setHandle(user.getEffectiveName());
    holder.setActive(true);
    holder.setRoleManaged(false);
    BankHolder saved = holderRepository.save(holder);
    bankAuditService.record(
        BankAuditEventType.HOLDER_REGISTERED, null, null, user.getId(), saved.getHandle());
    return bankHolderMapper.toDto(saved, BigDecimal.ZERO);
  }

  /**
   * Toggles a holder's active flag (REQ-BANK-003). Deactivation blocks new incoming postings; the
   * recorded custody and the history stay untouched.
   *
   * @param holderId the holder row
   * @param request the new flag plus the echoed optimistic-locking version
   * @return the updated holder row incl. fresh custody totals
   * @throws NotFoundException when the holder does not exist
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankHolderDto updateHolder(
      @NotNull UUID holderId, @NotNull UpdateBankHolderRequest request) {
    BankHolder holder =
        Entities.require(holderRepository.findById(holderId), "Bank holder not found");
    OptimisticLock.check(holder.getVersion(), request.version(), BankHolder.class, holderId);
    boolean wasActive = holder.isActive();
    holder.setActive(request.active());
    BankHolder saved = holderRepository.save(holder);
    if (wasActive != saved.isActive()) {
      bankAuditService.record(
          saved.isActive()
              ? BankAuditEventType.HOLDER_REACTIVATED
              : BankAuditEventType.HOLDER_DEACTIVATED,
          null,
          null,
          saved.getUser() != null ? saved.getUser().getId() : null,
          saved.getHandle());
    }
    BigDecimal total = holderPostingRepository.holderTotal(holderId);
    return bankHolderMapper.toDto(saved, total);
  }
}
