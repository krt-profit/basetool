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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.BankAccountMapper;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBalancePointDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankAccountLifecycleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankAccountRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RenameBankAccountRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankAccountBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankBookingRow;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.util.BankAmounts;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account lifecycle and read surface of the Kartell bank (REQ-BANK-001/-002): create, rename,
 * close, reopen, listings, detail and booking history.
 *
 * <p>Balances are computed from the ledger on read (ADR-0010) and joined in batch (REQ-DATA-003).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BankAccountService {

  private static final Duration DELTA_WINDOW = Duration.ofDays(30);

  private final BankAccountRepository accountRepository;
  private final BankPostingRepository postingRepository;
  private final BankHolderPostingRepository holderPostingRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final BankAccountMapper bankAccountMapper;
  private final BankAuditService bankAuditService;
  private final BankBookingRequestService bankBookingRequestService;
  private final BankGrantService bankGrantService;
  private final BankApprovalLimitService bankApprovalLimitService;

  /**
   * Pages over the accounts the caller may see, optionally filtered by a case-insensitive name or
   * account-number fragment and by status and type (REQ-BANK-053).
   *
   * <p>Management sees all accounts, employees only their granted ones (REQ-BANK-010). The query is
   * a bound parameter; {@code %} and {@code _} in it act as LIKE wildcards.
   *
   * @param management whether the caller has the management perspective
   * @param userId the caller's user id, for the employee filter
   * @param query the name/account-number fragment, or {@code null}/blank for no text filter
   * @param statuses the account statuses to include; never empty
   * @param types the account types to include; never empty
   * @param pageable page, size and whitelisted sort
   * @return one page of matching accounts with balances
   */
  public Page<BankAccountDto> getAccounts(
      boolean management,
      @NotNull UUID userId,
      @Nullable String query,
      @NotNull Set<BankAccountStatus> statuses,
      @NotNull Set<BankAccountType> types,
      @NotNull Pageable pageable) {
    String pattern = query == null || query.isBlank() ? "" : query;
    Page<BankAccount> page =
        management
            ? accountRepository.findAllFiltered(pattern, statuses, types, pageable)
            : accountRepository.findGrantedToFiltered(userId, pattern, statuses, types, pageable);
    Map<UUID, BigDecimal> balances =
        balancesFor(page.getContent().stream().map(BankAccount::getId).toList());
    return page.map(
        account ->
            bankAccountMapper.toDto(
                account, balances.getOrDefault(account.getId(), BigDecimal.ZERO)));
  }

  /**
   * Loads one account's detail aggregate: account, balance, 30-day delta, booking count and the
   * caller's capabilities. Approval limits are always read-only here (REQ-BANK-041).
   *
   * @param accountId the account
   * @param capabilities the caller's evaluated capabilities on the account
   * @return the detail payload
   * @throws NotFoundException when the account does not exist
   */
  @NotNull
  public BankAccountDetailDto getAccountDetail(
      @NotNull UUID accountId, @NotNull BankCapabilitiesDto capabilities) {
    BankAccount account = requireAccount(accountId);
    BigDecimal balance = postingRepository.accountBalance(accountId);
    Instant cutoff = Instant.now().minus(DELTA_WINDOW);
    BigDecimal delta =
        postingRepository.postingSlicesSince(List.of(accountId), cutoff).stream()
            .map(slice -> slice.amount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new BankAccountDetailDto(
        bankAccountMapper.toDto(account, balance),
        delta,
        postingRepository.countByAccountId(accountId),
        capabilities,
        bankApprovalLimitService.assemble(account, false));
  }

  /**
   * Pages over one account's booking history with counter-accounts and holder annotations resolved
   * in batch (REQ-BANK-018), optionally within an inclusive period (REQ-BANK-051).
   *
   * @param accountId the account
   * @param pageable page, size and whitelisted sort (default newest first)
   * @param from inclusive lower bound on the booking instant, or {@code null} for none
   * @param to inclusive upper bound on the booking instant, or {@code null} for none
   * @return one page of booking rows in the period
   */
  public Page<BankBookingDto> getBookings(
      @NotNull UUID accountId,
      @NotNull Pageable pageable,
      @Nullable Instant from,
      @Nullable Instant to) {
    requireAccount(accountId);
    Page<BankBookingRow> rows = postingRepository.findBookings(accountId, from, to, pageable);
    List<UUID> txIds =
        rows.getContent().stream().map(BankBookingRow::transactionId).distinct().toList();
    List<UUID> transferTxIds =
        rows.getContent().stream()
            .filter(r -> r.type() == BankTransactionType.TRANSFER)
            .map(BankBookingRow::transactionId)
            .distinct()
            .toList();
    Map<UUID, List<BankCounterLeg>> accountLegsByTx =
        transferTxIds.isEmpty()
            ? Map.of()
            : postingRepository.findLegsByTransactionIds(transferTxIds).stream()
                .collect(Collectors.groupingBy(BankCounterLeg::transactionId));
    Map<UUID, List<BankHolderLeg>> holderLegsByTx =
        txIds.isEmpty()
            ? Map.of()
            : holderPostingRepository.findHolderLegsByTransactionIds(txIds).stream()
                .collect(Collectors.groupingBy(BankHolderLeg::transactionId));
    return rows.map(row -> toBookingDto(accountId, row, accountLegsByTx, holderLegsByTx));
  }

  /**
   * Builds the account's end-of-day balance series for a period plus its balance target
   * (REQ-BANK-049), using {@link BankBalanceSeriesCalculator}.
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the balance series (oldest first) and the balance target ({@code null} when unset)
   * @throws NotFoundException when the account does not exist
   */
  @NotNull
  public BankBalanceSeriesDto getBalanceSeries(
      @NotNull UUID accountId, @NotNull Instant from, @NotNull Instant to) {
    BankAccount account = requireAccount(accountId);
    BigDecimal opening = postingRepository.accountBalanceBefore(accountId, from);
    List<BankBalancePointDto> points =
        BankBalanceSeriesCalculator.compute(
            opening, postingRepository.postingSlicesInRange(accountId, from, to), from, to);
    return new BankBalanceSeriesDto(points, account.getBalanceTarget());
  }

  /**
   * Creates an account (REQ-BANK-001/-002/-030): validates the owner reference, enforces
   * uniqueness, draws the next {@code KB-} number and audits the creation.
   *
   * <p>Management may create any type; a non-management employee only {@code SPECIAL} accounts, and
   * is granted full capability on them (ADR-0040).
   *
   * @param request validated creation payload
   * @param management whether the caller has the bank-management perspective
   * @param creatorUserId the caller's user id for the auto-grant; may be {@code null}
   * @return the created account with its zero balance
   * @throws AccessDeniedException when a non-management employee creates a non-{@code SPECIAL} type
   * @throws BadRequestException when the owner reference does not match the type
   * @throws DuplicateEntityException when the singleton or per-org-unit uniqueness is violated
   * @throws NotFoundException when the referenced org unit does not exist
   */
  @Transactional
  public BankAccountDto createAccount(
      @NotNull CreateBankAccountRequest request, boolean management, @Nullable UUID creatorUserId) {
    if (request.type() != BankAccountType.SPECIAL && !management) {
      throw new AccessDeniedException(
          "Bank employees may only create special accounts; other account types are"
              + " bank-management-only");
    }
    BankAccount account = new BankAccount();
    account.setName(request.name().trim());
    account.setType(request.type());
    account.setStatus(BankAccountStatus.ACTIVE);
    switch (request.type()) {
      case ORG_UNIT -> {
        if (request.orgUnitId() == null) {
          throw new BadRequestException("An ORG_UNIT account requires an org unit reference");
        }
        if (request.areaName() != null && !request.areaName().isBlank()) {
          throw new BadRequestException("An ORG_UNIT account must not carry an area name");
        }
        OrgUnit orgUnit =
            Entities.require(orgUnitRepository.findById(request.orgUnitId()), "Org unit not found");
        if (orgUnit.getKind() != OrgUnitKind.SQUADRON
            && orgUnit.getKind() != OrgUnitKind.SPECIAL_COMMAND) {
          throw new BadRequestException(
              "An ORG_UNIT account must reference a Staffel or Spezialkommando");
        }
        if (accountRepository.existsByOrgUnitId(orgUnit.getId())) {
          throw new DuplicateEntityException("The org unit already owns a bank account");
        }
        account.setOrgUnit(orgUnit);
      }
      case AREA -> {
        if (request.orgUnitId() == null) {
          throw new BadRequestException("An AREA account requires its Bereich org unit");
        }
        requireNoAreaName(request);
        OrgUnit bereich =
            Entities.require(orgUnitRepository.findById(request.orgUnitId()), "Org unit not found");
        if (bereich.getKind() != OrgUnitKind.BEREICH) {
          throw new BadRequestException("An AREA account must reference a Bereich org unit");
        }
        if (accountRepository.existsByOrgUnitId(bereich.getId())) {
          throw new DuplicateEntityException("The Bereich already owns a bank account");
        }
        account.setOrgUnit(bereich);
      }
      case CARTEL -> {
        requireNoAreaName(request);
        if (accountRepository.existsByType(request.type())) {
          throw new DuplicateEntityException(
              "The " + request.type() + " account already exists (singleton)");
        }
        if (request.orgUnitId() != null) {
          OrgUnit ol =
              Entities.require(
                  orgUnitRepository.findById(request.orgUnitId()), "Org unit not found");
          if (ol.getKind() != OrgUnitKind.ORGANISATIONSLEITUNG) {
            throw new BadRequestException(
                "The CARTEL account must reference the Organisationsleitung");
          }
          if (accountRepository.existsByOrgUnitId(ol.getId())) {
            throw new DuplicateEntityException(
                "The Organisationsleitung already owns a bank account");
          }
          account.setOrgUnit(ol);
        }
      }
      case CARTEL_BANK -> {
        requireNoOrgUnit(request);
        requireNoAreaName(request);
        if (accountRepository.existsByType(request.type())) {
          throw new DuplicateEntityException(
              "The " + request.type() + " account already exists (singleton)");
        }
      }
      case SPECIAL -> {
        requireNoOrgUnit(request);
        requireNoAreaName(request);
      }
      default -> throw new BadRequestException("Unsupported bank account type: " + request.type());
    }
    account.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    BankAccount saved = accountRepository.save(account);
    bankAuditService.record(
        BankAuditEventType.ACCOUNT_CREATED,
        saved.getId(),
        null,
        null,
        saved.getAccountNo() + " " + saved.getName() + " (" + saved.getType() + ")");
    if (!management && creatorUserId != null) {
      bankGrantService.createGrant(
          new CreateBankGrantRequest(creatorUserId, saved.getId(), true, true, true));
    }
    return bankAccountMapper.toDto(saved, BigDecimal.ZERO);
  }

  /**
   * Renames an account; the only mutable attribute outside the lifecycle (REQ-BANK-001).
   *
   * @param accountId the account
   * @param request the new name plus the echoed optimistic-locking version
   * @return the updated account incl. its balance
   * @throws NotFoundException when the account does not exist
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankAccountDto renameAccount(
      @NotNull UUID accountId, @NotNull RenameBankAccountRequest request) {
    BankAccount account = requireAccount(accountId);
    requireVersionMatch(account, request.version());
    String oldName = account.getName();
    account.setName(request.name().trim());
    BankAccount saved = accountRepository.save(account);
    bankAuditService.record(
        BankAuditEventType.ACCOUNT_RENAMED,
        saved.getId(),
        null,
        null,
        "'" + oldName + "' -> '" + saved.getName() + "'");
    return bankAccountMapper.toDto(saved, postingRepository.accountBalance(accountId));
  }

  /**
   * Sets or clears an account's balance target ("Kontostandsziel", REQ-BANK-036); audited.
   *
   * @param accountId the account
   * @param target the new positive whole target, or {@code null} to clear it
   * @param version the echoed optimistic-locking version
   * @return the updated account with its balance
   * @throws NotFoundException when the account does not exist
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankAccountDto setBalanceTarget(
      @NotNull UUID accountId, @Nullable BigDecimal target, @NotNull Long version) {
    BankAccount account = requireAccount(accountId);
    requireVersionMatch(account, version);
    account.setBalanceTarget(target);
    BankAccount saved = accountRepository.save(account);
    bankAuditService.record(
        target == null
            ? BankAuditEventType.BALANCE_TARGET_CLEARED
            : BankAuditEventType.BALANCE_TARGET_SET,
        saved.getId(),
        null,
        null,
        target == null ? null : "target=" + BankAmounts.plain(target));
    return bankAccountMapper.toDto(saved, postingRepository.accountBalance(accountId));
  }

  /**
   * Sets or clears the KRT account's (CARTEL) approval thresholds T1/T2 (REQ-BANK-047); audited.
   *
   * <p>Both {@code null} clears the ladder; when both are set, {@code T2} must be at least {@code
   * T1}.
   *
   * @param accountId the KRT account
   * @param employeeCeiling the bank-employee ceiling {@code T1}, or {@code null} to clear it
   * @param areaLeadCeiling the Bereichsleiter-Profit ceiling {@code T2}, or {@code null} to clear
   *     it
   * @param version the echoed optimistic-locking version
   * @return the updated account with its balance
   * @throws NotFoundException when the account does not exist
   * @throws BadRequestException when the account is not the KRT account or {@code T2 < T1}
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankAccountDto setCartelApprovalTiers(
      @NotNull UUID accountId,
      @Nullable BigDecimal employeeCeiling,
      @Nullable BigDecimal areaLeadCeiling,
      @NotNull Long version) {
    BankAccount account = requireAccount(accountId);
    if (account.getType() != BankAccountType.CARTEL) {
      throw new BadRequestException(
          "Approval thresholds may be configured only on the KRT (CARTEL) account");
    }
    if (employeeCeiling != null
        && areaLeadCeiling != null
        && areaLeadCeiling.compareTo(employeeCeiling) < 0) {
      throw new BadRequestException(
          "The area-lead ceiling must be at or above the bank-employee ceiling");
    }
    requireVersionMatch(account, version);
    account.setEmployeeApprovalCeiling(employeeCeiling);
    account.setAreaLeadApprovalCeiling(areaLeadCeiling);
    BankAccount saved = accountRepository.save(account);
    boolean cleared = employeeCeiling == null && areaLeadCeiling == null;
    bankAuditService.record(
        cleared
            ? BankAuditEventType.CARTEL_APPROVAL_TIERS_CLEARED
            : BankAuditEventType.CARTEL_APPROVAL_TIERS_SET,
        saved.getId(),
        null,
        null,
        cleared
            ? null
            : "employee="
                + (employeeCeiling == null ? "-" : BankAmounts.plain(employeeCeiling))
                + ",areaLead="
                + (areaLeadCeiling == null ? "-" : BankAmounts.plain(areaLeadCeiling)));
    return bankAccountMapper.toDto(saved, postingRepository.accountBalance(accountId));
  }

  /**
   * Closes an account with a zero balance (REQ-BANK-002); it stays readable but rejects postings.
   *
   * @param accountId the account
   * @param request the echoed optimistic-locking version
   * @return the updated account
   * @throws NotFoundException when the account does not exist
   * @throws BankConflictException with {@code BANK_ACCOUNT_NOT_EMPTY} on a non-zero balance
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankAccountDto closeAccount(
      @NotNull UUID accountId, @NotNull BankAccountLifecycleRequest request) {
    BankAccount account = requireAccount(accountId);
    requireVersionMatch(account, request.version());
    BigDecimal balance = postingRepository.accountBalance(accountId);
    if (balance.signum() != 0) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_ACCOUNT_NOT_EMPTY,
          "Only an account with a zero balance can be closed",
          Map.of("accountNo", account.getAccountNo(), "balance", BankAmounts.plain(balance)));
    }
    if (bankBookingRequestService.hasOpenRequests(accountId)) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_ACCOUNT_HAS_PENDING_REQUESTS,
          "The account has open booking requests; decide them before closing",
          Map.of("accountNo", account.getAccountNo()));
    }
    account.setStatus(BankAccountStatus.CLOSED);
    BankAccount saved = accountRepository.save(account);
    bankAuditService.record(
        BankAuditEventType.ACCOUNT_CLOSED, saved.getId(), null, null, saved.getAccountNo());
    return bankAccountMapper.toDto(saved, balance);
  }

  /**
   * Reopens a closed account, restoring full booking capability (REQ-BANK-002).
   *
   * @param accountId the account
   * @param request the echoed optimistic-locking version
   * @return the updated account
   * @throws NotFoundException when the account does not exist
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankAccountDto reopenAccount(
      @NotNull UUID accountId, @NotNull BankAccountLifecycleRequest request) {
    BankAccount account = requireAccount(accountId);
    requireVersionMatch(account, request.version());
    account.setStatus(BankAccountStatus.ACTIVE);
    BankAccount saved = accountRepository.save(account);
    bankAuditService.record(
        BankAuditEventType.ACCOUNT_REOPENED, saved.getId(), null, null, saved.getAccountNo());
    return bankAccountMapper.toDto(saved, postingRepository.accountBalance(accountId));
  }

  /**
   * Batch-computes the balances of many accounts as one grouped statement (REQ-DATA-003).
   *
   * @param accountIds the accounts
   * @return balance per account id; accounts without postings are absent (treat as zero)
   */
  public Map<UUID, BigDecimal> balancesFor(@NotNull List<UUID> accountIds) {
    if (accountIds.isEmpty()) {
      return Map.of();
    }
    return postingRepository.accountBalances(accountIds).stream()
        .collect(Collectors.toMap(BankAccountBalance::accountId, BankAccountBalance::balance));
  }

  /**
   * Maps one booking row to its DTO, resolving the holder annotation and, for transfers, the
   * counter account from the batched legs (ADR-0039).
   *
   * @param accountId the account whose history is rendered
   * @param row the projected booking row
   * @param accountLegsByTx account legs of the page's transfers, grouped by transaction
   * @param holderLegsByTx holder legs of the page's transactions, grouped by transaction
   * @return the booking DTO
   */
  @NotNull
  private BankBookingDto toBookingDto(
      @NotNull UUID accountId,
      @NotNull BankBookingRow row,
      @NotNull Map<UUID, List<BankCounterLeg>> accountLegsByTx,
      @NotNull Map<UUID, List<BankHolderLeg>> holderLegsByTx) {
    List<BankHolderLeg> holderLegs = holderLegsByTx.getOrDefault(row.transactionId(), List.of());
    String holderHandle =
        row.type() == BankTransactionType.WIPE_RESET
            ? null
            : matchHolderHandle(holderLegs, row.amount().signum());
    String counterAccountNo = null;
    String counterAccountName = null;
    String counterHolderHandle = null;
    if (row.type() == BankTransactionType.TRANSFER) {
      BankCounterLeg counter =
          accountLegsByTx.getOrDefault(row.transactionId(), List.of()).stream()
              .filter(l -> !l.postingId().equals(row.postingId()))
              .findFirst()
              .orElse(null);
      if (counter != null) {
        counterAccountNo = counter.accountNo();
        counterAccountName = counter.accountName();
      }
      counterHolderHandle = matchHolderHandle(holderLegs, -row.amount().signum());
    }
    return new BankBookingDto(
        row.postingId(),
        row.transactionId(),
        row.type(),
        row.amount(),
        holderHandle,
        row.note(),
        row.justification(),
        row.staffNote(),
        row.createdAt(),
        row.reversedTransactionId(),
        counterAccountNo,
        counterAccountName,
        counterHolderHandle,
        false,
        row.transferFee(),
        row.counterpartyHandle(),
        row.counterpartyOrgUnitName());
  }

  /**
   * Picks the handle of the holder leg whose amount sign matches {@code sign} (ADR-0039).
   *
   * @param holderLegs the transaction's holder legs
   * @param sign the wanted amount sign (+1 or -1; 0 never matches)
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
   * Loads an account or fails with 404.
   *
   * @param accountId the account id
   * @return the account entity
   */
  private BankAccount requireAccount(@NotNull UUID accountId) {
    return Entities.require(accountRepository.findById(accountId), "Bank account not found");
  }

  /**
   * Explicit optimistic-lock check (HangarService precedent): fail fast with the standard 409
   * before touching any property when the client echoed a stale version.
   *
   * @param account the loaded account
   * @param version the client-echoed version
   */
  private static void requireVersionMatch(@NotNull BankAccount account, @NotNull Long version) {
    OptimisticLock.check(account.getVersion(), version, BankAccount.class, account.getId());
  }

  /**
   * Rejects an org-unit reference on the types that carry none: {@code CARTEL_BANK} and {@code
   * SPECIAL} (REQ-ORG-019).
   *
   * @param request the creation payload
   */
  private static void requireNoOrgUnit(@NotNull CreateBankAccountRequest request) {
    if (request.orgUnitId() != null) {
      throw new BadRequestException(
          "A " + request.type() + " account must not carry an org unit reference");
    }
  }

  /**
   * Rejects a free-form area name; an AREA account is owned by its Bereich via the org-unit FK
   * (REQ-ORG-019).
   *
   * @param request the creation payload
   */
  private static void requireNoAreaName(@NotNull CreateBankAccountRequest request) {
    if (request.areaName() != null && !request.areaName().isBlank()) {
      throw new BadRequestException(
          "A " + request.type() + " account must not carry a free-form area name");
    }
  }
}
