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

import de.greluc.krt.profit.basetool.backend.bank.api.BankAmounts;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The responsibility settings of the org-unit bank page (REQ-BANK-035, REQ-BANK-036, REQ-BANK-041):
 * balance target, view grants and approval limits.
 *
 * <p>Every decision comes from the {@link OrgUnitBankAccessService} seam (ADR-0020); this service
 * validates the role buckets, delegates persistence and audit to {@link
 * OrgUnitBankVisibilityService} and {@link OrgUnitBankApprovalLimitService}, and assembles the
 * settings snapshot.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankSettingsService {

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
      List.of(Roles.LOGISTICIAN, Roles.MISSION_MANAGER);

  private final OrgUnitBankAccessService accessService;
  private final BankAccountRepository bankAccountRepository;
  private final BankAccountViewGrantRepository viewGrantRepository;
  private final UserRepository userRepository;
  private final BankApprovalLimitService bankApprovalLimitService;
  private final BankAuditService bankAuditService;
  private final OrgUnitBankVisibilityService orgUnitBankVisibilityService;
  private final OrgUnitBankApprovalLimitService orgUnitBankApprovalLimitService;

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
    BankAccount account = accessService.requireAccount(accountId);
    if (!accessService.canSetTarget(account) && !accessService.canConfigureVisibility(account)) {
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanSetTarget(account);
    OptimisticLock.check(account.getVersion(), version, BankAccount.class, account.getId());
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureVisibility(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureVisibility(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureVisibility(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureVisibility(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureVisibility(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureVisibility(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureApprovalLimits(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureApprovalLimits(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureApprovalLimits(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureApprovalLimits(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureApprovalLimits(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureApprovalLimits(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureApprovalLimits(account);
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
    BankAccount account = accessService.requireAccount(accountId);
    accessService.requireCanConfigureApprovalLimits(account);
    orgUnitBankApprovalLimitService.clearUser(account, userId);
    return toSettingsDto(account);
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
            .filter(grant ->
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
    boolean canConfigureApprovalLimits = accessService.canConfigureApprovalLimits(account);
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
        accessService.canSetTarget(account),
        accessService.canConfigureVisibility(account),
        OrgUnitBankAccessService.visibilityConfigurable(account.getType()),
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
  private static BankAccountViewGranteeKind requireValidRoleBucket(
      @NotNull BankAccount account, @NotNull String roleCode) {
    if (!availableRoleCodes(account).contains(roleCode)) {
      throw new BadRequestException("Unknown visibility role bucket for this account: " + roleCode);
    }
    return roleBucketsGlobal(account.getType())
        ? BankAccountViewGranteeKind.GLOBAL_ROLE
        : BankAccountViewGranteeKind.MEMBERSHIP_ROLE;
  }

  /**
   * {@code true} iff the account type has an all-members visibility bucket (same set as {@link
   * OrgUnitBankAccessService#visibilityConfigurable}).
   *
   * @param type the account type
   * @return whether the all-members bucket applies
   */
  private static boolean allMembersSupported(@NotNull BankAccountType type) {
    return OrgUnitBankAccessService.visibilityConfigurable(type);
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
}
