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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountApprovalLimit;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.Department;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountRefDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankApprovalLimitsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankViewUserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankAccountBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountApprovalLimitRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountViewGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BereichRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.util.BankAmounts;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single org-unit-aware seam between the bank and the org-unit oversight scope (ADR-0020): it
 * authorizes against {@link OwnerScopeService} and then reuses the org-unit-blind bank code ({@link
 * BankAccountService}, {@link BankStatementReportService}).
 *
 * <p>Serves the org-unit bank page: the account cards, the read-only drill-in, booking requests,
 * and the responsibility settings (view grants, balance target, approval limits). Visibility is
 * decided by {@link #canView}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgUnitBankAccessService {

  /**
   * Read-only capabilities handed to org-unit viewers — no deposit/withdraw/transfer (management).
   */
  private static final BankCapabilitiesDto READ_ONLY_CAPABILITIES =
      new BankCapabilitiesDto(false, false, false, false);

  /**
   * Visibility role buckets a Staffel account's Staffelleiter may toggle (MembershipRole names).
   */
  private static final List<String> SQUADRON_ROLE_BUCKETS =
      List.of(
          MembershipRole.KOMMANDOLEITER.name(),
          MembershipRole.STELLV_KOMMANDOLEITER.name(),
          MembershipRole.ENSIGN.name());

  /**
   * Visibility role buckets a Bereich account's Bereichsleiter may toggle (MembershipRole names).
   */
  private static final List<String> BEREICH_ROLE_BUCKETS =
      List.of(MembershipRole.BEREICHSKOORDINATOR.name(), MembershipRole.BEREICHSOPERATOR.name());

  /**
   * Global-role buckets the OL may toggle for a Sonderkonto (no owning unit ⇒ no membership ranks).
   * Stored without the {@code ROLE_} prefix; evaluated via {@code
   * hasReachableRole(Roles.authority(code))}.
   */
  private static final List<String> SPECIAL_GLOBAL_ROLE_BUCKETS =
      List.of(Roles.OFFICER, Roles.LOGISTICIAN, Roles.MISSION_MANAGER);

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;
  private final BankAccountRepository bankAccountRepository;
  private final BankPostingRepository bankPostingRepository;
  private final BankAccountViewGrantRepository viewGrantRepository;
  private final BankAccountApprovalLimitRepository approvalLimitRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final BereichRepository bereichRepository;
  private final UserRepository userRepository;
  private final BankAccountService bankAccountService;
  private final BankApprovalLimitService bankApprovalLimitService;
  private final BankStatementReportService bankStatementReportService;
  private final BankBookingRequestService bankBookingRequestService;
  private final BankAuditService bankAuditService;
  private final OrgUnitBankVisibilityService orgUnitBankVisibilityService;
  private final OrgUnitBankApprovalLimitService orgUnitBankApprovalLimitService;

  /**
   * Lists every active account the caller may view, with balance, 30-day trend, balance target and
   * settings affordance (REQ-BANK-021).
   *
   * @return the visible account balances ordered by account number; empty when none
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<OrgUnitBankBalanceDto> listOverseenOrgUnitBalances() {
    boolean admin = authHelperService.isAdmin();
    ScopePredicate viewScope = ownerScopeService.currentOversightScope();
    List<BankAccount> active =
        bankAccountRepository.findAllByOrderByAccountNoAsc().stream()
            .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
            .toList();
    if (active.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<BankAccountViewGrant>> grantsByAccount =
        viewGrantRepository
            .findByAccountIdIn(active.stream().map(BankAccount::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(grant -> grant.getAccount().getId()));
    List<BankAccount> visible =
        active.stream()
            .filter(
                account ->
                    admin
                        || canViewInternal(
                            account,
                            viewScope,
                            grantsByAccount.getOrDefault(account.getId(), List.of())))
            .toList();
    if (visible.isEmpty()) {
      return List.of();
    }
    Map<UUID, BigDecimal> balances = balancesByAccountId(visible);
    Map<UUID, List<BankPostingSlice>> slicesByAccount = slicesByAccountId(visible);
    Map<UUID, List<BankAccountApprovalLimit>> limitsByAccount =
        approvalLimitRepository
            .findByAccountIdIn(visible.stream().map(BankAccount::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(limit -> limit.getAccount().getId()));
    return visible.stream()
        .map(
            account -> {
              BigDecimal balance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
              List<BankPostingSlice> slices =
                  slicesByAccount.getOrDefault(account.getId(), List.of());
              BigDecimal delta = BankTrendCalculator.windowDelta(slices);
              boolean canRequest = isRequestCapable(account);
              boolean approvalExempt = canRequest && isApprovalExempt(account);
              BigDecimal approvalLimit =
                  canRequest && !approvalExempt
                      ? resolveApplicableLimit(
                          account, limitsByAccount.getOrDefault(account.getId(), List.of()))
                      : null;
              return toDto(
                  account,
                  balance,
                  canRequest,
                  delta,
                  BankTrendCalculator.sparkline(balance, delta, slices),
                  canManageSettings(account),
                  approvalLimit,
                  approvalExempt);
            })
        .toList();
  }

  /**
   * Returns the read-only detail of an account the caller may view (REQ-BANK-038), with all
   * bank-staff capabilities off.
   *
   * @param accountId the account to open
   * @return the read-only detail
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view the account
   */
  @NotNull
  @Transactional(readOnly = true)
  public OrgUnitBankAccountDetailDto getViewableAccountDetail(@NotNull UUID accountId) {
    BankAccount account = requireViewableAccount(accountId);
    BankAccountDetailDto detail =
        bankAccountService.getAccountDetail(accountId, READ_ONLY_CAPABILITIES);
    boolean canRequest = isRequestCapable(account);
    boolean approvalExempt = canRequest && isApprovalExempt(account);
    BigDecimal applicableLimit =
        canRequest && !approvalExempt ? resolveApplicableLimit(account) : null;
    return new OrgUnitBankAccountDetailDto(
        detail,
        true,
        canSetTarget(account),
        canConfigureVisibility(account),
        canRequest,
        canConfigureApprovalLimits(account),
        applicableLimit,
        approvalExempt);
  }

  /**
   * Returns one page of an account's booking history with the holder handles redacted
   * (REQ-BANK-038).
   *
   * @param accountId the account to read
   * @param pageable page, size and whitelisted sort
   * @param from inclusive lower bound on the booking instant, or {@code null}
   * @param to inclusive upper bound on the booking instant, or {@code null}
   * @return one page of redacted booking rows
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view the account
   */
  @NotNull
  @Transactional(readOnly = true)
  public Page<BankBookingDto> getViewableAccountBookings(
      @NotNull UUID accountId,
      @NotNull Pageable pageable,
      @Nullable Instant from,
      @Nullable Instant to) {
    requireViewableAccount(accountId);
    return bankAccountService
        .getBookings(accountId, pageable, from, to)
        .map(OrgUnitBankAccessService::redact);
  }

  /**
   * Returns the balance-over-time series of an account the caller may view (REQ-BANK-049).
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the balance series and the account's balance target
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view the account
   */
  @NotNull
  @Transactional(readOnly = true)
  public BankBalanceSeriesDto getViewableBalanceSeries(
      @NotNull UUID accountId, @NotNull Instant from, @NotNull Instant to) {
    requireViewableAccount(accountId);
    return bankAccountService.getBalanceSeries(accountId, from, to);
  }

  /**
   * Generates the holder-redacted statement PDF for an account the caller may view (REQ-BANK-038)
   * and records the {@code STATEMENT_EXPORTED} audit event.
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @param userZone the zone to render timestamps in, or {@code null} for UTC
   * @return the redacted PDF bytes
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view the account
   */
  @NotNull
  @Transactional
  public byte[] exportViewableStatement(
      @NotNull UUID accountId,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone) {
    requireViewableAccount(accountId);
    return bankStatementReportService.generateStatement(accountId, from, to, userZone, true);
  }

  /**
   * Returns an account's responsibility settings: balance target, view grants and the controls the
   * caller may use (REQ-BANK-035).
   *
   * @param accountId the account
   * @return the settings snapshot
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may neither set the target nor configure
   *     visibility
   */
  @NotNull
  @Transactional(readOnly = true)
  public OrgUnitBankAccountSettingsDto getAccountSettings(@NotNull UUID accountId) {
    BankAccount account = requireAccount(accountId);
    if (!canSetTarget(account) && !canConfigureVisibility(account)) {
      throw new AccessDeniedException("The caller may not manage this account's settings");
    }
    return toSettingsDto(account);
  }

  /**
   * Sets, changes or clears an account's balance target as its responsible holder (REQ-BANK-036).
   *
   * @param accountId the account
   * @param target the new target, or {@code null} to clear it
   * @param version the echoed optimistic-lock version
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not set the target
   * @throws ObjectOptimisticLockingFailureException on a version mismatch
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setBalanceTarget(
      @NotNull UUID accountId, @Nullable BigDecimal target, long version) {
    BankAccount account = requireAccount(accountId);
    requireCanSetTarget(account);
    requireVersionMatch(account, version);
    account.setBalanceTarget(target);
    bankAccountRepository.saveAndFlush(account);
    bankAuditService.record(
        target == null
            ? BankAuditEventType.BALANCE_TARGET_CLEARED
            : BankAuditEventType.BALANCE_TARGET_SET,
        accountId,
        null,
        null,
        target == null ? null : "target=" + BankAmounts.plain(target));
    return toSettingsDto(account);
  }

  /**
   * Adds a role-bucket view grant to an account (REQ-BANK-035); idempotent.
   *
   * <p>The bucket is a {@code MembershipRole} for org-unit accounts and a global role for a
   * Sonderkonto.
   *
   * @param accountId the account
   * @param roleCode the role bucket to grant
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto addRoleVisibility(
      @NotNull UUID accountId, @NotNull String roleCode) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    BankAccountViewGranteeKind kind = requireValidRoleBucket(account, roleCode);
    orgUnitBankVisibilityService.grantRole(account, kind, roleCode);
    return toSettingsDto(account);
  }

  /**
   * Removes a role-bucket view grant from an account (REQ-BANK-035).
   *
   * @param accountId the account
   * @param roleCode the role bucket to revoke
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto removeRoleVisibility(
      @NotNull UUID accountId, @NotNull String roleCode) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    BankAccountViewGranteeKind kind = requireValidRoleBucket(account, roleCode);
    orgUnitBankVisibilityService.revokeRole(account, kind, roleCode);
    return toSettingsDto(account);
  }

  /**
   * Enables or disables the all-members view grant of an account (REQ-BANK-035); idempotent.
   *
   * @param accountId the account
   * @param enabled whether all members may view the account
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   * @throws BadRequestException when the account type has no all-members bucket
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setAllMembersVisibility(
      @NotNull UUID accountId, boolean enabled) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    if (!allMembersSupported(account.getType())) {
      throw new BadRequestException("This account type has no all-members visibility bucket");
    }
    orgUnitBankVisibilityService.setAllMembers(account, enabled);
    return toSettingsDto(account);
  }

  /**
   * Grants an individual user view access to an account (REQ-BANK-035). Idempotent.
   *
   * @param accountId the account
   * @param userId the user to grant
   * @return the refreshed settings
   * @throws NotFoundException when the account or the user does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto addUserVisibility(
      @NotNull UUID accountId, @NotNull UUID userId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    orgUnitBankVisibilityService.grantUser(account, userId);
    return toSettingsDto(account);
  }

  /**
   * Revokes an individual user's view access to an account (REQ-BANK-035).
   *
   * @param accountId the account
   * @param userId the user to revoke
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto removeUserVisibility(
      @NotNull UUID accountId, @NotNull UUID userId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    orgUnitBankVisibilityService.revokeUser(account, userId);
    return toSettingsDto(account);
  }

  /**
   * Enables or disables the area-cascade view grant of an {@code AREA} account (REQ-BANK-048);
   * idempotent.
   *
   * @param accountId the account
   * @param enabled whether the whole area cascade may view the account
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure visibility
   * @throws BadRequestException when the account type has no area-members bucket
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setAreaMembersVisibility(
      @NotNull UUID accountId, boolean enabled) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureVisibility(account);
    if (!areaMembersSupported(account.getType())) {
      throw new BadRequestException("This account type has no area-members visibility bucket");
    }
    orgUnitBankVisibilityService.setAreaMembers(account, enabled);
    return toSettingsDto(account);
  }

  /**
   * Upserts a role-bucket approval limit on an account (REQ-BANK-041).
   *
   * @param accountId the account
   * @param roleCode the role bucket, a {@code MembershipRole} name
   * @param limit the non-negative ceiling in whole aUEC
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setRoleApprovalLimit(
      @NotNull UUID accountId, @NotNull String roleCode, @NotNull BigDecimal limit) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    requireValidLimitRoleBucket(account, roleCode);
    orgUnitBankApprovalLimitService.setRole(account, roleCode, limit);
    return toSettingsDto(account);
  }

  /**
   * Removes a role-bucket approval limit from an account (REQ-BANK-041).
   *
   * @param accountId the account
   * @param roleCode the role bucket to clear
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto clearRoleApprovalLimit(
      @NotNull UUID accountId, @NotNull String roleCode) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    requireValidLimitRoleBucket(account, roleCode);
    orgUnitBankApprovalLimitService.clearRole(account, roleCode);
    return toSettingsDto(account);
  }

  /**
   * Upserts the all-members approval limit of an account (REQ-BANK-041).
   *
   * @param accountId the account
   * @param limit the non-negative ceiling in whole aUEC
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   * @throws BadRequestException when the account type has no all-members tier
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setAllMembersApprovalLimit(
      @NotNull UUID accountId, @NotNull BigDecimal limit) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    if (!BankApprovalLimitService.allMembersSupported(account.getType())) {
      throw new BadRequestException("This account type has no all-members approval-limit tier");
    }
    orgUnitBankApprovalLimitService.setAllMembers(account, limit);
    return toSettingsDto(account);
  }

  /**
   * Removes the all-members approval limit from an account (REQ-BANK-041).
   *
   * @param accountId the account
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto clearAllMembersApprovalLimit(@NotNull UUID accountId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    orgUnitBankApprovalLimitService.clearAllMembers(account);
    return toSettingsDto(account);
  }

  /**
   * Upserts the area-cascade approval limit of an {@code AREA} account (REQ-BANK-048).
   *
   * @param accountId the account
   * @param limit the non-negative ceiling in whole aUEC
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   * @throws BadRequestException when the account type has no area-members tier
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setAreaMembersApprovalLimit(
      @NotNull UUID accountId, @NotNull BigDecimal limit) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    if (!BankApprovalLimitService.areaMembersSupported(account.getType())) {
      throw new BadRequestException("This account type has no area-members approval-limit tier");
    }
    orgUnitBankApprovalLimitService.setAreaMembers(account, limit);
    return toSettingsDto(account);
  }

  /**
   * Removes the area-cascade approval limit from an {@code AREA} account (REQ-BANK-048).
   *
   * @param accountId the account
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto clearAreaMembersApprovalLimit(@NotNull UUID accountId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    orgUnitBankApprovalLimitService.clearAreaMembers(account);
    return toSettingsDto(account);
  }

  /**
   * Upserts an individual user's approval limit on an account, which overrides every other tier for
   * that user (REQ-BANK-041).
   *
   * @param accountId the account
   * @param userId the user the limit addresses
   * @param limit the non-negative ceiling in whole aUEC
   * @return the refreshed settings
   * @throws NotFoundException when the account or the user does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto setUserApprovalLimit(
      @NotNull UUID accountId, @NotNull UUID userId, @NotNull BigDecimal limit) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    orgUnitBankApprovalLimitService.setUser(account, userId, limit);
    return toSettingsDto(account);
  }

  /**
   * Removes an individual user's approval limit from an account (REQ-BANK-041).
   *
   * @param accountId the account
   * @param userId the user whose limit to clear
   * @return the refreshed settings
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  @NotNull
  @Transactional
  public OrgUnitBankAccountSettingsDto clearUserApprovalLimit(
      @NotNull UUID accountId, @NotNull UUID userId) {
    BankAccount account = requireAccount(accountId);
    requireCanConfigureApprovalLimits(account);
    orgUnitBankApprovalLimitService.clearUser(account, userId);
    return toSettingsDto(account);
  }

  /**
   * Raises a booking request and delegates persistence to {@link BankBookingRequestService}.
   *
   * <p>A deposit may be requested by anyone against any active account and needs no approval
   * (REQ-BANK-042). A withdrawal or transfer requires {@link #canView}, a request-capable account
   * and the approval routing of {@link #resolveApplicableLimit} (REQ-BANK-039).
   *
   * @param request the create payload
   * @return the created pending request
   * @throws AccessDeniedException when a withdrawal or transfer caller may not view the account
   * @throws BadRequestException when the destination account is missing or superfluous for the
   *     type, or the account accepts no withdrawals or transfers
   * @throws NotFoundException when the source account does not exist
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto createBookingRequest(@NotNull CreateBankBookingRequest request) {
    BankAccount account =
        Entities.require(
            bankAccountRepository.findById(request.sourceAccountId()), "Bank account not found");

    if (request.type() == BankBookingRequestType.DEPOSIT) {
      if (request.targetAccountId() != null) {
        throw new BadRequestException("A deposit request must not carry a destination account");
      }
      return bankBookingRequestService.create(
          account.getId(),
          BankBookingRequestType.DEPOSIT,
          request.amount(),
          request.note(),
          null,
          null,
          false,
          null,
          null,
          request.splitEnabled(),
          request.splitPercent(),
          null,
          null);
    }

    if (!canView(account)) {
      throw new AccessDeniedException("The caller may not raise a booking request on this account");
    }
    if (!isRequestCapable(account)) {
      throw new BadRequestException("This account does not accept booking requests");
    }
    UUID targetAccountId = null;
    if (request.type() == BankBookingRequestType.TRANSFER) {
      if (request.targetAccountId() == null) {
        throw new BadRequestException("A transfer request requires a destination account");
      }
      targetAccountId = request.targetAccountId();
    } else if (request.targetAccountId() != null) {
      throw new BadRequestException("A non-transfer request must not carry a destination account");
    }
    ApprovalRouting routing = resolveApprovalRouting(account, request.amount());
    return bankBookingRequestService.create(
        account.getId(),
        request.type(),
        request.amount(),
        request.note(),
        request.justification(),
        targetAccountId,
        routing.requiresOwnerApproval(),
        routing.applicableLimit(),
        routing.requiredApprover(),
        false,
        null,
        request.counterpartyUserId(),
        request.counterpartyOrgUnitId());
  }

  /**
   * Approval routing of a withdrawal or transfer request (REQ-BANK-041).
   *
   * @param requiresOwnerApproval whether confirmation needs the approver's attestation
   * @param applicableLimit the requester's display ceiling, or {@code null}
   * @param requiredApprover the approver class, or {@code null} when no approval is needed
   */
  private record ApprovalRouting(
      boolean requiresOwnerApproval,
      @Nullable BigDecimal applicableLimit,
      @Nullable BankRequestApprover requiredApprover) {}

  /**
   * Resolves the amount-band routing of the KRT ({@code CARTEL}) account (REQ-BANK-047, ADR-0109).
   *
   * <p>Up to {@code T1} needs no approver, up to {@code T2} the Bankleitung, above that the
   * Organisationsleitung; an unset {@code T1} counts as 0 and an unset {@code T2} as unbounded. The
   * applicable limit is always {@code T1}.
   *
   * @param account the KRT account
   * @param amount the whole-aUEC amount leaving the account
   * @return the resolved approval routing
   */
  @NotNull
  private ApprovalRouting resolveCartelApprovalRouting(
      @NotNull BankAccount account, @NotNull BigDecimal amount) {
    BigDecimal t1 =
        account.getEmployeeApprovalCeiling() == null
            ? BigDecimal.ZERO
            : account.getEmployeeApprovalCeiling();
    BigDecimal t2 = account.getAreaLeadApprovalCeiling();
    if (amount.compareTo(t1) <= 0) {
      return new ApprovalRouting(false, t1, null);
    }
    if (t2 == null || amount.compareTo(t2) <= 0) {
      return new ApprovalRouting(true, t1, BankRequestApprover.BANK_MANAGEMENT);
    }
    return new ApprovalRouting(true, t1, BankRequestApprover.ORGANISATIONSLEITUNG);
  }

  /**
   * Files a pending, band-routed request for a bank-staff direct withdrawal or transfer that
   * exceeds the KRT employee ceiling {@code T1} (REQ-BANK-047).
   *
   * <p>Relies on the caller having passed the bank capability gate; only amount, note,
   * justification and target carry over.
   *
   * @param accountId the KRT source account
   * @param type {@code WITHDRAWAL} or {@code TRANSFER}
   * @param amount the whole-aUEC amount leaving the account
   * @param note the optional note
   * @param justification the justification
   * @param targetAccountId the destination for a {@code TRANSFER}, else {@code null}
   * @return the filed pending booking request
   * @throws NotFoundException when the account does not exist
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto raiseCartelDirectBookingRequest(
      @NotNull UUID accountId,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount,
      @Nullable String note,
      @Nullable String justification,
      @Nullable UUID targetAccountId) {
    BankAccount account =
        Entities.require(bankAccountRepository.findById(accountId), "Bank account not found");
    ApprovalRouting routing =
        isApprovalExempt(account)
            ? new ApprovalRouting(false, null, null)
            : resolveCartelApprovalRouting(account, amount);
    return bankBookingRequestService.create(
        accountId,
        type,
        amount,
        note,
        justification,
        targetAccountId,
        routing.requiresOwnerApproval(),
        routing.applicableLimit(),
        routing.requiredApprover(),
        false,
        null,
        null,
        null);
  }

  /**
   * Resolves which approver a debit request of this amount needs and the requester's applicable
   * ceiling (REQ-BANK-041); shared by the create and {@linkplain #updateOwnBookingRequest edit}
   * paths.
   *
   * @param account the source account
   * @param amount the requested amount
   * @return the approval snapshot to stamp on the request
   */
  @NotNull
  private ApprovalRouting resolveApprovalRouting(
      @NotNull BankAccount account, @NotNull BigDecimal amount) {
    if (isApprovalExempt(account)) {
      return new ApprovalRouting(false, null, null);
    }
    if (account.getType() == BankAccountType.CARTEL) {
      return resolveCartelApprovalRouting(account, amount);
    }
    BigDecimal limit = resolveApplicableLimit(account);
    boolean needsApproval = limit == null || amount.compareTo(limit) > 0;
    return new ApprovalRouting(
        needsApproval, limit, needsApproval ? BankRequestApprover.RESPONSIBLE_HOLDER : null);
  }

  /**
   * Applies a requester's correction to their own pending booking request and re-derives the
   * approval snapshot (REQ-BANK-056).
   *
   * <p>{@link BankBookingRequestService#updateOwn} re-checks ownership, state and version under the
   * row lock.
   *
   * @param requestId the request to correct
   * @param request the corrected values and the echoed version
   * @return the updated request
   * @throws NotFoundException when the request does not exist or belongs to another user
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto updateOwnBookingRequest(
      @NotNull UUID requestId, @NotNull UpdateBankBookingRequest request) {
    BankBookingRequest existing =
        Entities.require(
            bankBookingRequestRepository.findById(requestId), "Booking request not found");
    UUID caller = authHelperService.currentUserId().orElse(null);
    if (caller == null || !caller.equals(existing.getRequestedBy())) {
      throw new NotFoundException("Booking request not found");
    }
    ApprovalRouting routing = resolveApprovalRouting(existing.getAccount(), request.amount());
    return bankBookingRequestService.updateOwn(
        requestId,
        request,
        routing.requiresOwnerApproval(),
        routing.applicableLimit(),
        routing.requiredApprover());
  }

  /**
   * Lists the caller's own booking requests with the staff note removed (REQ-BANK-022,
   * REQ-BANK-054).
   *
   * @return the caller's requests, newest first
   */
  @NotNull
  public List<BankBookingRequestDto> listOwnBookingRequests() {
    return bankBookingRequestService.listForCurrentRequester().stream()
        .map(BankBookingRequestDto::withoutStaffNote)
        .toList();
  }

  /**
   * Cancels the caller's own pending booking request (REQ-BANK-022).
   *
   * @param requestId the request to cancel
   * @param version the echoed optimistic-locking version
   * @return the cancelled request
   */
  @NotNull
  public BankBookingRequestDto cancelOwnBookingRequest(@NotNull UUID requestId, long version) {
    return bankBookingRequestService.cancelOwn(requestId, version);
  }

  /**
   * Lists every active account as a transfer-request destination (REQ-BANK-040): a requester may
   * transfer from a viewable source account to <em>any</em> active account. Balance-free references
   * only; ordered by account number.
   *
   * @return the active accounts as transfer targets; never {@code null}
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<BankAccountRefDto> listTransferTargetAccounts() {
    return bankAccountRepository.findAllByOrderByAccountNoAsc().stream()
        .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
        .map(
            account ->
                new BankAccountRefDto(
                    account.getId(), account.getAccountNo(), account.getName(), account.getType()))
        .toList();
  }

  /**
   * Lists the booking requests the caller may act on: all requests of accounts they hold, the KRT
   * band requests routed to them, or everything for an admin (REQ-BANK-046).
   *
   * @return the requests the caller may act on; empty when none
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<BankBookingRequestDto> listRequestsForResponsibleAccounts() {
    boolean admin = authHelperService.isAdmin();
    boolean bankMgmt = hasBankManagement();
    Set<UUID> responsibleIds = new LinkedHashSet<>();
    Set<UUID> cartelIds = new LinkedHashSet<>();
    for (BankAccount account : bankAccountRepository.findAllByOrderByAccountNoAsc()) {
      if (admin || isResponsibleHolder(account)) {
        responsibleIds.add(account.getId());
      }
      if (account.getType() == BankAccountType.CARTEL) {
        cartelIds.add(account.getId());
      }
    }
    Set<UUID> candidateIds = new LinkedHashSet<>(responsibleIds);
    if (bankMgmt) {
      candidateIds.addAll(cartelIds);
    }
    if (candidateIds.isEmpty()) {
      return List.of();
    }
    boolean olMember = ownerScopeService.currentUserIsOlMember();
    return bankBookingRequestService.listForAccounts(candidateIds).stream()
        .filter(request -> canSeeForeignRequest(request, admin, bankMgmt, olMember, responsibleIds))
        .toList();
  }

  /**
   * Whether a foreign booking request is visible to the caller: a KRT band request only to its band
   * approver, any other request to the account's responsible holder, everything to an admin.
   *
   * @param request the request row
   * @param admin whether the caller is an admin
   * @param bankMgmt whether the caller holds {@code BANK_MANAGEMENT}
   * @param olMember whether the caller is an OL member
   * @param responsibleIds ids of the accounts the caller is responsible holder of
   * @return whether the caller may see the request
   */
  private static boolean canSeeForeignRequest(
      @NotNull BankBookingRequestDto request,
      boolean admin,
      boolean bankMgmt,
      boolean olMember,
      @NotNull Set<UUID> responsibleIds) {
    if (admin) {
      return true;
    }
    String approver = request.requiredApprover();
    if (BankRequestApprover.BANK_MANAGEMENT.name().equals(approver)) {
      return bankMgmt;
    }
    if (BankRequestApprover.ORGANISATIONSLEITUNG.name().equals(approver)) {
      return olMember;
    }
    return responsibleIds.contains(request.accountId());
  }

  /**
   * Grants the required approver's approval of an over-limit booking request (REQ-BANK-041).
   *
   * @param requestId the request to approve
   * @return the updated request
   * @throws NotFoundException when the request does not exist
   * @throws AccessDeniedException when the caller may not approve the request
   * @throws BadRequestException when the request needs no approval or is no longer pending
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto grantOwnerApproval(@NotNull UUID requestId) {
    return applyOwnerApproval(requestId, true);
  }

  /**
   * Revokes a previously granted in-app approval for a booking request (REQ-BANK-041/-046).
   *
   * @param requestId the request whose approval to revoke
   * @return the updated request
   * @throws NotFoundException when the request does not exist
   * @throws AccessDeniedException when the caller may not approve the request
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto revokeOwnerApproval(@NotNull UUID requestId) {
    return applyOwnerApproval(requestId, false);
  }

  /**
   * Loads and locks a request, authorizes the caller via {@link #canApprove}, and delegates the
   * mutation and audit to {@link
   * BankBookingRequestService#applyOwnerApprovalWithinTransaction(BankBookingRequest, boolean)}.
   *
   * @param requestId the request
   * @param granted {@code true} to grant, {@code false} to revoke the approval
   * @return the updated request
   */
  @NotNull
  private BankBookingRequestDto applyOwnerApproval(@NotNull UUID requestId, boolean granted) {
    BankBookingRequest request =
        Entities.require(
            bankBookingRequestRepository.findByIdForUpdate(requestId), "Booking request not found");
    if (!canApprove(request)) {
      throw new AccessDeniedException("The caller may not approve this request");
    }
    return bankBookingRequestService.applyOwnerApprovalWithinTransaction(request, granted);
  }

  /**
   * Whether the caller may grant or revoke a request's approval: an admin, the KRT band approver,
   * or otherwise the account's responsible holder.
   *
   * @param request the request entity
   * @return whether the caller may act on its approval
   */
  private boolean canApprove(@NotNull BankBookingRequest request) {
    if (authHelperService.isAdmin()) {
      return true;
    }
    BankRequestApprover approver = request.getRequiredApprover();
    if (approver == null) {
      return isResponsibleHolder(request.getAccount());
    }
    return switch (approver) {
      case RESPONSIBLE_HOLDER -> isResponsibleHolder(request.getAccount());
      case BANK_MANAGEMENT -> hasBankManagement();
      case ORGANISATIONSLEITUNG -> ownerScopeService.currentUserIsOlMember();
    };
  }

  /**
   * Whether the caller reaches the {@code BANK_MANAGEMENT} role, the middle-band KRT approver
   * (REQ-BANK-047).
   *
   * @return whether the caller is or outranks the Bankleitung
   */
  private boolean hasBankManagement() {
    return authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT));
  }

  /**
   * Whether the caller may view the account; loads its view grants on demand.
   *
   * @param account the account to test
   * @return {@code true} iff the caller may view it
   */
  @Transactional(readOnly = true)
  public boolean canView(@NotNull BankAccount account) {
    if (authHelperService.isAdmin()) {
      return true;
    }
    return canViewInternal(
        account,
        ownerScopeService.currentOversightScope(),
        viewGrantRepository.findByAccountId(account.getId()));
  }

  /**
   * Non-admin view decision for one account from the caller's oversight scope and the account's
   * view grants.
   *
   * @param account the account
   * @param viewScope the caller's cascading oversight scope
   * @param grants the account's view grants
   * @return {@code true} iff the caller may view the account
   */
  private boolean canViewInternal(
      @NotNull BankAccount account,
      @NotNull ScopePredicate viewScope,
      @NotNull List<BankAccountViewGrant> grants) {
    UUID owner = owningOrgUnitId(account);
    return switch (account.getType()) {
      case ORG_UNIT, AREA ->
          owner != null && (viewScope.permits(owner) || matchesOrgUnitGrant(owner, grants));
      case CARTEL ->
          (owner != null && viewScope.permits(owner)) || authHelperService.isMemberOrAbove();
      case CARTEL_BANK -> isProfitBereichsleiter();
      case SPECIAL -> currentUserCanAutoViewSpecial() || matchesSpecialGrant(grants);
    };
  }

  /**
   * Whether a view grant of an org-unit account admits the caller: a role bucket held on the owning
   * unit, all-members as a member of it, or an individual grant.
   *
   * @param owningOrgUnitId the account's owning org unit id
   * @param grants the account's view grants
   * @return {@code true} iff a grant admits the caller
   */
  private boolean matchesOrgUnitGrant(
      @NotNull UUID owningOrgUnitId, @NotNull List<BankAccountViewGrant> grants) {
    Optional<UUID> userId = authHelperService.currentUserId();
    for (BankAccountViewGrant grant : grants) {
      boolean match =
          switch (grant.getGranteeKind()) {
            case MEMBERSHIP_ROLE -> {
              MembershipRole role = parseMembershipRole(grant.getRoleCode());
              yield role != null
                  && ownerScopeService.currentUserHoldsRoleOnOrgUnit(owningOrgUnitId, role);
            }
            case ALL_MEMBERS -> ownerScopeService.currentUserIsMemberOfOrgUnit(owningOrgUnitId);
            case AREA_MEMBERS ->
                ownerScopeService.currentUserIsMemberOfAreaCascade(owningOrgUnitId);
            case USER -> userId.isPresent() && userId.get().equals(grant.getGranteeUserId());
            case GLOBAL_ROLE -> false;
          };
      if (match) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a view grant of a {@code SPECIAL} account admits the caller: a reachable global role,
   * all-members, or an individual grant.
   *
   * @param grants the account's view grants
   * @return {@code true} iff a grant admits the caller
   */
  private boolean matchesSpecialGrant(@NotNull List<BankAccountViewGrant> grants) {
    Optional<UUID> userId = authHelperService.currentUserId();
    for (BankAccountViewGrant grant : grants) {
      boolean match =
          switch (grant.getGranteeKind()) {
            case GLOBAL_ROLE ->
                authHelperService.hasReachableRole(Roles.authority(grant.getRoleCode()));
            case ALL_MEMBERS -> authHelperService.isMemberOrAbove();
            case USER -> userId.isPresent() && userId.get().equals(grant.getGranteeUserId());
            case MEMBERSHIP_ROLE, AREA_MEMBERS -> false;
          };
      if (match) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code true} iff the caller auto-views Sonderkonten (REQ-BANK-037): an OL member or any
   * Bereichsleiter. Admins are handled separately by the callers.
   *
   * @return {@code true} iff the caller is an OL member or a Bereichsleiter
   */
  private boolean currentUserCanAutoViewSpecial() {
    return ownerScopeService.currentUserIsOlMember()
        || ownerScopeService.currentUserIsBereichsleiter();
  }

  /**
   * Whether the caller is the {@code BEREICHSLEITER} of a {@code Department.PROFIT} Bereich, the
   * responsible holder of the {@code CARTEL_BANK} account (REQ-BANK-037).
   *
   * @return {@code true} iff the caller leads a PROFIT Bereich
   */
  private boolean isProfitBereichsleiter() {
    Set<UUID> profitBereichIds =
        bereichRepository.findByDepartment(Department.PROFIT).stream()
            .map(Bereich::getId)
            .collect(Collectors.toSet());
    return profitBereichIds.stream()
        .anyMatch(
            id ->
                ownerScopeService.currentUserHoldsRoleOnOrgUnit(id, MembershipRole.BEREICHSLEITER));
  }

  /**
   * Whether the caller is the account's derived responsible holder (REQ-BANK-034); Sonderkonten
   * have none.
   *
   * @param account the account
   * @return {@code true} iff the caller is its responsible holder
   */
  private boolean isResponsibleHolder(@NotNull BankAccount account) {
    UUID owner = owningOrgUnitId(account);
    return switch (account.getType()) {
      case ORG_UNIT -> {
        if (owner == null) {
          yield false;
        }
        MembershipRole holderRole =
            account.getOrgUnit().getKind() == OrgUnitKind.SPECIAL_COMMAND
                ? MembershipRole.SK_LEAD
                : MembershipRole.STAFFELLEITER;
        yield ownerScopeService.currentUserHoldsRoleOnOrgUnit(owner, holderRole);
      }
      case AREA ->
          owner != null
              && ownerScopeService.currentUserHoldsRoleOnOrgUnit(
                  owner, MembershipRole.BEREICHSLEITER);
      case CARTEL -> ownerScopeService.currentUserIsOlMember();
      case CARTEL_BANK -> isProfitBereichsleiter();
      case SPECIAL -> false;
    };
  }

  /**
   * Whether the caller, as the account's responsible holder, is exempt from every approval gate
   * including the KRT ladder (REQ-BANK-041).
   *
   * @param account the account a request would debit
   * @return whether the caller may debit it without any approver's sign-off
   */
  private boolean isApprovalExempt(@NotNull BankAccount account) {
    return isResponsibleHolder(account);
  }

  /**
   * Whether the caller may configure the account's view grants (REQ-BANK-035): the responsible
   * holder of an org-unit account, or an OL member or bank management for a Sonderkonto.
   *
   * @param account the account
   * @return {@code true} iff the caller may add or remove view grants
   */
  private boolean canConfigureVisibility(@NotNull BankAccount account) {
    if (authHelperService.isAdmin()) {
      return visibilityConfigurable(account.getType());
    }
    return switch (account.getType()) {
      case ORG_UNIT, AREA -> isResponsibleHolder(account);
      case SPECIAL ->
          ownerScopeService.currentUserIsOlMember()
              || authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT));
      case CARTEL, CARTEL_BANK -> false;
    };
  }

  /**
   * {@code true} iff the caller may set/clear the account's balance target from the org-unit side
   * (REQ-BANK-036): the responsible holder. Sonderkonto targets are bank-staff-only.
   *
   * @param account the account
   * @return {@code true} iff the caller may set the target
   */
  private boolean canSetTarget(@NotNull BankAccount account) {
    if (authHelperService.isAdmin()) {
      return true;
    }
    return switch (account.getType()) {
      case ORG_UNIT, AREA, CARTEL, CARTEL_BANK -> isResponsibleHolder(account);
      case SPECIAL -> false;
    };
  }

  /**
   * {@code true} iff the caller may open the account's settings panel at all (set the target and/or
   * configure visibility). Drives the per-card settings affordance.
   *
   * @param account the account
   * @return {@code true} iff the caller may manage the account's settings
   */
  private boolean canManageSettings(@NotNull BankAccount account) {
    return canSetTarget(account) || canConfigureVisibility(account);
  }

  /**
   * Whether the account accepts booking requests: {@code ACTIVE} and of type {@code ORG_UNIT},
   * {@code AREA} or {@code CARTEL}.
   *
   * @param account the account
   * @return whether a booking request may be raised against it
   */
  private boolean isRequestCapable(@NotNull BankAccount account) {
    return account.getStatus() == BankAccountStatus.ACTIVE
        && BankApprovalLimitService.configurable(account.getType());
  }

  /**
   * Whether the caller may set or clear the account's approval limits (REQ-BANK-041): its
   * responsible holder, bank management or an admin.
   *
   * @param account the account
   * @return whether the caller may configure approval limits
   */
  private boolean canConfigureApprovalLimits(@NotNull BankAccount account) {
    if (!BankApprovalLimitService.audienceLimitsSupported(account.getType())) {
      return false;
    }
    return authHelperService.isAdmin()
        || authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT))
        || isResponsibleHolder(account);
  }

  /**
   * Asserts the caller may configure the account's approval limits.
   *
   * @param account the account
   * @throws AccessDeniedException when the caller may not configure approval limits
   */
  private void requireCanConfigureApprovalLimits(@NotNull BankAccount account) {
    if (!canConfigureApprovalLimits(account)) {
      throw new AccessDeniedException(
          "The caller may not configure this account's approval limits");
    }
  }

  /**
   * Validates an approval-limit role-bucket code against the account's limit buckets.
   *
   * @param account the account
   * @param roleCode the role code to validate
   * @throws BadRequestException when the role code is not a valid limit bucket for this account
   */
  private void requireValidLimitRoleBucket(@NotNull BankAccount account, @NotNull String roleCode) {
    if (!BankApprovalLimitService.roleBuckets(account).contains(roleCode)) {
      throw new BadRequestException(
          "Unknown approval-limit role bucket for this account: " + roleCode);
    }
  }

  /**
   * Resolves the current caller's applicable approval limit for an account (REQ-BANK-041), loading
   * the account's limit rows on demand. See {@link #resolveApplicableLimit(BankAccount, List)}.
   *
   * @param account the account
   * @return the caller's limit, or {@code null} = unlimited (no approval needed)
   */
  @Nullable
  private BigDecimal resolveApplicableLimit(@NotNull BankAccount account) {
    return resolveApplicableLimit(
        account, approvalLimitRepository.findByAccountId(account.getId()));
  }

  /**
   * Resolves the caller's applicable approval limit (REQ-BANK-041, REQ-BANK-047).
   *
   * <p>For the KRT account it is the employee ceiling {@code T1}. Otherwise an individual limit
   * wins; else the maximum of every matching membership tier, where the all-members tier matches
   * only members of the owning unit. {@code null} means no tier matched, so approval is required.
   *
   * @param account the account
   * @param limits the account's approval-limit rows; may be empty
   * @return the caller's limit, or {@code null} when no tier matched
   */
  @Nullable
  private BigDecimal resolveApplicableLimit(
      @NotNull BankAccount account, @NotNull List<BankAccountApprovalLimit> limits) {
    if (account.getType() == BankAccountType.CARTEL) {
      return account.getEmployeeApprovalCeiling();
    }
    if (limits.isEmpty()) {
      return null;
    }
    Optional<UUID> userId = authHelperService.currentUserId();
    for (BankAccountApprovalLimit limit : limits) {
      if (limit.getGranteeKind() == BankAccountViewGranteeKind.USER
          && userId.isPresent()
          && userId.get().equals(limit.getGranteeUserId())) {
        return limit.getLimitAmount();
      }
    }
    UUID owner = owningOrgUnitId(account);
    BigDecimal best = null;
    for (BankAccountApprovalLimit limit : limits) {
      boolean matches =
          switch (limit.getGranteeKind()) {
            case MEMBERSHIP_ROLE -> {
              MembershipRole role = parseMembershipRole(limit.getRoleCode());
              yield owner != null
                  && role != null
                  && ownerScopeService.currentUserHoldsRoleOnOrgUnit(owner, role);
            }
            case GLOBAL_ROLE ->
                authHelperService.hasReachableRole(Roles.authority(limit.getRoleCode()));
            case AREA_MEMBERS ->
                owner != null && ownerScopeService.currentUserIsMemberOfAreaCascade(owner);
            case ALL_MEMBERS ->
                owner != null && ownerScopeService.currentUserIsMemberOfOrgUnit(owner);
            case USER -> false;
          };
      if (matches) {
        best = best == null ? limit.getLimitAmount() : best.max(limit.getLimitAmount());
      }
    }
    return best;
  }

  /**
   * Builds the settings snapshot for one account.
   *
   * @param account the account
   * @return the settings DTO
   */
  @NotNull
  private OrgUnitBankAccountSettingsDto toSettingsDto(@NotNull BankAccount account) {
    List<BankAccountViewGrant> grants = viewGrantRepository.findByAccountId(account.getId());
    List<String> grantedRoleCodes =
        grants.stream()
            .filter(
                grant ->
                    grant.getGranteeKind() == BankAccountViewGranteeKind.MEMBERSHIP_ROLE
                        || grant.getGranteeKind() == BankAccountViewGranteeKind.GLOBAL_ROLE)
            .map(BankAccountViewGrant::getRoleCode)
            .toList();
    boolean allMembersGranted =
        grants.stream()
            .anyMatch(grant -> grant.getGranteeKind() == BankAccountViewGranteeKind.ALL_MEMBERS);
    boolean areaMembersGranted =
        grants.stream()
            .anyMatch(grant -> grant.getGranteeKind() == BankAccountViewGranteeKind.AREA_MEMBERS);
    List<UUID> grantedUserIds =
        grants.stream()
            .filter(grant -> grant.getGranteeKind() == BankAccountViewGranteeKind.USER)
            .map(BankAccountViewGrant::getGranteeUserId)
            .toList();
    Map<UUID, String> names =
        grantedUserIds.isEmpty()
            ? Map.of()
            : userRepository.findAllById(grantedUserIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEffectiveName));
    List<OrgUnitBankViewUserDto> grantedUsers =
        grantedUserIds.stream()
            .map(id -> new OrgUnitBankViewUserDto(id, names.getOrDefault(id, "")))
            .toList();
    OrgUnit orgUnit = account.getOrgUnit();
    boolean canConfigureApprovalLimits = canConfigureApprovalLimits(account);
    BankApprovalLimitsDto approvalLimits =
        bankApprovalLimitService.assemble(account, canConfigureApprovalLimits);
    return new OrgUnitBankAccountSettingsDto(
        account.getId(),
        account.getAccountNo(),
        account.getName(),
        account.getType(),
        orgUnit == null ? null : orgUnit.getKind(),
        account.getBalanceTarget(),
        account.getVersion(),
        canSetTarget(account),
        canConfigureVisibility(account),
        visibilityConfigurable(account.getType()),
        allMembersSupported(account.getType()),
        areaMembersSupported(account.getType()),
        roleBucketsGlobal(account.getType()),
        availableRoleCodes(account),
        grantedRoleCodes,
        allMembersGranted,
        areaMembersGranted,
        grantedUsers,
        canConfigureApprovalLimits,
        approvalLimits);
  }

  /**
   * The role-bucket codes the caller may toggle for an account, in display order: the squadron /
   * Bereich sub-ranks for org-unit accounts, the global roles for a Sonderkonto, none otherwise.
   *
   * @param account the account
   * @return the available role codes (possibly empty)
   */
  @NotNull
  private static List<String> availableRoleCodes(@NotNull BankAccount account) {
    if (account.getType() == BankAccountType.SPECIAL) {
      return SPECIAL_GLOBAL_ROLE_BUCKETS;
    }
    OrgUnit orgUnit = account.getOrgUnit();
    if (orgUnit == null) {
      return List.of();
    }
    return switch (orgUnit.getKind()) {
      case SQUADRON -> SQUADRON_ROLE_BUCKETS;
      case BEREICH -> BEREICH_ROLE_BUCKETS;
      case SPECIAL_COMMAND, ORGANISATIONSLEITUNG -> List.of();
    };
  }

  /**
   * Validates a role-bucket code against the account and returns its grant kind: {@code
   * GLOBAL_ROLE} for a Sonderkonto, else {@code MEMBERSHIP_ROLE}.
   *
   * @param account the account
   * @param roleCode the role code to validate
   * @return the grant kind to use
   * @throws BadRequestException when the role code is not a valid bucket for this account
   */
  @NotNull
  private BankAccountViewGranteeKind requireValidRoleBucket(
      @NotNull BankAccount account, @NotNull String roleCode) {
    if (!availableRoleCodes(account).contains(roleCode)) {
      throw new BadRequestException("Unknown visibility role bucket for this account: " + roleCode);
    }
    return roleBucketsGlobal(account.getType())
        ? BankAccountViewGranteeKind.GLOBAL_ROLE
        : BankAccountViewGranteeKind.MEMBERSHIP_ROLE;
  }

  /**
   * {@code true} iff the account type supports configurable visibility at all (ORG_UNIT / AREA /
   * SPECIAL); {@code CARTEL} (fixed all-members) and {@code CARTEL_BANK} (internal) do not.
   *
   * @param type the account type
   * @return whether visibility is configurable
   */
  private static boolean visibilityConfigurable(@NotNull BankAccountType type) {
    return type == BankAccountType.ORG_UNIT
        || type == BankAccountType.AREA
        || type == BankAccountType.SPECIAL;
  }

  /**
   * {@code true} iff the account type has an all-members visibility bucket (same set as {@link
   * #visibilityConfigurable}).
   *
   * @param type the account type
   * @return whether the all-members bucket applies
   */
  private static boolean allMembersSupported(@NotNull BankAccountType type) {
    return visibilityConfigurable(type);
  }

  /**
   * {@code true} iff the account type has a "Mitglieder des Bereichs" cascade visibility bucket —
   * only {@code AREA} (Bereichskonto) accounts (REQ-BANK-048).
   *
   * @param type the account type
   * @return whether the area-members visibility bucket applies
   */
  private static boolean areaMembersSupported(@NotNull BankAccountType type) {
    return type == BankAccountType.AREA;
  }

  /**
   * {@code true} iff the account type's role buckets are global role codes (only {@code SPECIAL});
   * org-unit accounts use {@code MembershipRole} buckets.
   *
   * @param type the account type
   * @return whether the role buckets are global roles
   */
  private static boolean roleBucketsGlobal(@NotNull BankAccountType type) {
    return type == BankAccountType.SPECIAL;
  }

  /**
   * Loads an account and asserts the caller may view it.
   *
   * @param accountId the account id
   * @return the account entity
   * @throws NotFoundException when the account does not exist
   * @throws AccessDeniedException when the caller may not view it
   */
  @NotNull
  private BankAccount requireViewableAccount(@NotNull UUID accountId) {
    BankAccount account = requireAccount(accountId);
    if (!canView(account)) {
      throw new AccessDeniedException("The caller may not view this account");
    }
    return account;
  }

  /**
   * Loads an account or fails with 404.
   *
   * @param accountId the account id
   * @return the account entity
   * @throws NotFoundException when the account does not exist
   */
  @NotNull
  private BankAccount requireAccount(@NotNull UUID accountId) {
    return Entities.require(bankAccountRepository.findById(accountId), "Bank account not found");
  }

  /**
   * Asserts the caller may set the account's balance target.
   *
   * @param account the account
   * @throws AccessDeniedException when the caller may not set the target
   */
  private void requireCanSetTarget(@NotNull BankAccount account) {
    if (!canSetTarget(account)) {
      throw new AccessDeniedException("The caller may not set this account's balance target");
    }
  }

  /**
   * Asserts the caller may configure the account's visibility.
   *
   * @param account the account
   * @throws AccessDeniedException when the caller may not configure visibility
   */
  private void requireCanConfigureVisibility(@NotNull BankAccount account) {
    if (!canConfigureVisibility(account)) {
      throw new AccessDeniedException("The caller may not configure this account's visibility");
    }
  }

  /**
   * Checks the client-echoed version against the account before a mutation.
   *
   * @param account the loaded account
   * @param version the client-echoed version
   * @throws ObjectOptimisticLockingFailureException on a version mismatch
   */
  private static void requireVersionMatch(@NotNull BankAccount account, long version) {
    OptimisticLock.check(account.getVersion(), version, BankAccount.class, account.getId());
  }

  /**
   * The owning org-unit id of an account, or {@code null} when it has none.
   *
   * @param account the account
   * @return the owning org-unit id, or {@code null}
   */
  @Nullable
  private static UUID owningOrgUnitId(@NotNull BankAccount account) {
    return account.getOrgUnit() == null ? null : account.getOrgUnit().getId();
  }

  /**
   * Parses a {@code MembershipRole} name, returning {@code null} for an unknown code.
   *
   * @param code the role code
   * @return the role, or {@code null}
   */
  @Contract("null -> null")
  @Nullable
  private static MembershipRole parseMembershipRole(@Nullable String code) {
    if (code == null) {
      return null;
    }
    try {
      return MembershipRole.valueOf(code);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * Redacts a booking row for an org-unit viewer (REQ-BANK-038): nulls the holder and
   * counter-holder handles and the internal staff note; the counterparty stays visible.
   *
   * @param booking the bank-staff booking row
   * @return a redacted copy
   */
  @NotNull
  private static BankBookingDto redact(@NotNull BankBookingDto booking) {
    return new BankBookingDto(
        booking.postingId(),
        booking.transactionId(),
        booking.type(),
        booking.amount(),
        null,
        booking.note(),
        booking.justification(),
        null,
        booking.createdAt(),
        booking.reversedTransactionId(),
        booking.counterAccountNo(),
        booking.counterAccountName(),
        null,
        booking.intraAccount(),
        booking.transferFee(),
        booking.counterpartyHandle(),
        booking.counterpartyOrgUnitName());
  }

  /**
   * Batch-computes the balances of the given accounts in one grouped query (REQ-DATA-003).
   *
   * @param accounts the accounts to sum
   * @return a map of account id to balance for accounts that have at least one posting
   */
  @NotNull
  private Map<UUID, BigDecimal> balancesByAccountId(@NotNull List<BankAccount> accounts) {
    List<UUID> ids = accounts.stream().map(BankAccount::getId).toList();
    Map<UUID, BigDecimal> byAccount = new HashMap<>();
    for (BankAccountBalance row : bankPostingRepository.accountBalances(ids)) {
      byAccount.put(row.accountId(), row.balance());
    }
    return byAccount;
  }

  /**
   * Fetches the last 30 days of posting slices for the given accounts in one windowed query
   * (REQ-DATA-003) and groups them by account id, for the per-card 30-day trend (REQ-BANK-016).
   *
   * @param accounts the visible accounts
   * @return a map of account id to its in-window posting slices (only accounts with slices appear)
   */
  @NotNull
  private Map<UUID, List<BankPostingSlice>> slicesByAccountId(@NotNull List<BankAccount> accounts) {
    List<UUID> ids = accounts.stream().map(BankAccount::getId).toList();
    return bankPostingRepository
        .postingSlicesSince(ids, BankTrendCalculator.windowCutoff())
        .stream()
        .collect(Collectors.groupingBy(BankPostingSlice::accountId));
  }

  /**
   * Projects a visible account and its resolved figures into the balance-card DTO.
   *
   * @param account the visible account
   * @param balance the resolved balance; zero without postings
   * @param canRequest {@code true} iff this is the caller's own-level account
   * @param delta30d the signed 30-day net change
   * @param sparkline the 30 end-of-day balances, oldest first
   * @param canManageSettings whether the caller may open the account's settings
   * @param approvalLimit the caller's approval limit (REQ-BANK-041), or {@code null} when none
   *     applies
   * @param approvalExempt whether the caller is the responsible holder and bound by no ceiling
   * @return the balance-card DTO
   */
  @NotNull
  private OrgUnitBankBalanceDto toDto(
      @NotNull BankAccount account,
      @NotNull BigDecimal balance,
      boolean canRequest,
      @NotNull BigDecimal delta30d,
      @NotNull List<BigDecimal> sparkline,
      boolean canManageSettings,
      @Nullable BigDecimal approvalLimit,
      boolean approvalExempt) {
    OrgUnit orgUnit = account.getOrgUnit();
    return new OrgUnitBankBalanceDto(
        account.getId(),
        account.getAccountNo(),
        account.getName(),
        account.getStatus(),
        account.getType(),
        orgUnit == null ? null : orgUnit.getId(),
        orgUnit == null ? null : orgUnit.getName(),
        orgUnit == null ? null : orgUnit.getShorthand(),
        orgUnit == null ? null : orgUnit.getKind(),
        balance,
        canRequest,
        delta30d,
        sparkline,
        account.getBalanceTarget(),
        canManageSettings,
        approvalLimit,
        approvalExempt);
  }
}
