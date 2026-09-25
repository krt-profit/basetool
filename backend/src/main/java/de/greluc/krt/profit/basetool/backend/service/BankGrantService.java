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

import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.BankGrantMapper;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrantId;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankGrantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages per-account capability grants (REQ-BANK-009, ADR-0011). Grantees must hold the Bank
 * Employee role or above, org-unit membership is irrelevant (REQ-BANK-008), and every mutation is
 * audited in the same transaction (REQ-BANK-012).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BankGrantService {

  /**
   * Role codes whose holders are eligible grantees: the Bank Employee role itself plus the roles
   * that reach it through the hierarchy. Joining or leaving an org unit changes none of these
   * (REQ-BANK-008).
   */
  private static final Set<String> ELIGIBLE_ROLE_CODES =
      Set.of(Roles.BANK_EMPLOYEE, Roles.BANK_MANAGEMENT, Roles.ADMIN);

  private final BankAccountGrantRepository grantRepository;
  private final BankAccountRepository accountRepository;
  private final UserRepository userRepository;
  private final BankGrantMapper bankGrantMapper;
  private final BankAuditService bankAuditService;
  private final AuthHelperService authHelperService;

  /**
   * Lists grants for the matrix UI: per account, per employee, or all. Each row is marked inert
   * when the grantee currently lacks the Bank Employee role.
   *
   * @param accountId filter on one account, or {@code null}
   * @param userId filter on one grantee, or {@code null}
   * @return the matching grant rows
   */
  public List<BankGrantDto> getGrants(@Nullable UUID accountId, @Nullable UUID userId) {
    List<BankAccountGrant> grants;
    if (accountId != null) {
      grants = grantRepository.findByAccountId(accountId);
    } else if (userId != null) {
      grants = grantRepository.findByUserId(userId);
    } else {
      grants = grantRepository.findAllWithReferences();
    }
    return grants.stream()
        .map(grant -> bankGrantMapper.toDto(grant, hasBankRole(grant.getUser())))
        .toList();
  }

  /**
   * Creates a grant (REQ-BANK-009): the grantee must exist and currently hold the Bank Employee
   * role (or above); one grant row per (user, account).
   *
   * @param request validated creation payload
   * @return the created grant row
   * @throws NotFoundException when user or account do not exist
   * @throws BankConflictException with {@code BANK_GRANTEE_MISSING_ROLE} for ineligible grantees
   * @throws DuplicateEntityException when the (user, account) grant already exists
   */
  @Transactional
  public BankGrantDto createGrant(@NotNull CreateBankGrantRequest request) {
    User grantee = Entities.require(userRepository.findById(request.userId()), "User not found");
    BankAccount account =
        Entities.require(accountRepository.findById(request.accountId()), "Bank account not found");
    if (!hasBankRole(grantee)) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_GRANTEE_MISSING_ROLE,
          "Grants require the grantee to hold the Bank Employee role",
          Map.of("userHandle", grantee.getEffectiveName()));
    }
    BankAccountGrantId id = new BankAccountGrantId(grantee.getId(), account.getId());
    if (grantRepository.existsById(id)) {
      throw new DuplicateEntityException("The user already holds a grant on this account");
    }
    BankAccountGrant grant = new BankAccountGrant();
    grant.setId(id);
    grant.setUser(grantee);
    grant.setAccount(account);
    grant.setCanDeposit(request.canDeposit());
    grant.setCanWithdraw(request.canWithdraw());
    grant.setCanTransfer(request.canTransfer());
    grant.setGrantedBy(
        authHelperService.currentUserId().flatMap(userRepository::findById).orElse(null));
    BankAccountGrant saved = grantRepository.save(grant);
    bankAuditService.record(
        BankAuditEventType.GRANT_CREATED,
        account.getId(),
        null,
        grantee.getId(),
        flagString(saved));
    return bankGrantMapper.toDto(saved, true);
  }

  /**
   * Changes a grant's capability flags (REQ-BANK-009); the audit row carries before and after.
   *
   * @param userId the grantee half of the key
   * @param accountId the account half of the key
   * @param request the new flags plus the echoed optimistic-locking version
   * @return the updated grant row
   * @throws NotFoundException when the grant does not exist
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankGrantDto updateGrant(
      @NotNull UUID userId, @NotNull UUID accountId, @NotNull UpdateBankGrantRequest request) {
    BankAccountGrant grant = requireGrant(userId, accountId);
    OptimisticLock.check(
        grant.getVersion(),
        request.version(),
        BankAccountGrant.class,
        new BankAccountGrantId(userId, accountId));
    final String before = flagString(grant);
    grant.setCanDeposit(request.canDeposit());
    grant.setCanWithdraw(request.canWithdraw());
    grant.setCanTransfer(request.canTransfer());
    BankAccountGrant saved = grantRepository.save(grant);
    bankAuditService.record(
        BankAuditEventType.GRANT_UPDATED,
        accountId,
        null,
        userId,
        before + " -> " + flagString(saved));
    return bankGrantMapper.toDto(saved, hasBankRole(saved.getUser()));
  }

  /**
   * Revokes a grant: deletes the row, which removes the grantee's view access to the account
   * (REQ-BANK-009 — view access is the row's existence).
   *
   * @param userId the grantee half of the key
   * @param accountId the account half of the key
   * @throws NotFoundException when the grant does not exist
   */
  @Transactional
  public void deleteGrant(@NotNull UUID userId, @NotNull UUID accountId) {
    BankAccountGrant grant = requireGrant(userId, accountId);
    String flags = flagString(grant);
    grantRepository.delete(grant);
    bankAuditService.record(BankAuditEventType.GRANT_REVOKED, accountId, null, userId, flags);
  }

  /**
   * Loads a grant or fails with 404.
   *
   * @param userId the grantee half of the key
   * @param accountId the account half of the key
   * @return the grant entity
   */
  private BankAccountGrant requireGrant(@NotNull UUID userId, @NotNull UUID accountId) {
    return Entities.require(
        grantRepository.findById(new BankAccountGrantId(userId, accountId)),
        "Bank grant not found");
  }

  /**
   * Whether the user currently holds a bank role — synced from Keycloak into the local role rows by
   * {@code UserService.syncUser}. The only inert-grant case (REQ-BANK-009); org-unit memberships
   * play no part (REQ-BANK-008).
   *
   * @param user the grantee
   * @return {@code true} when the user's roles contain Bank Employee or a role above it
   */
  private static boolean hasBankRole(@NotNull User user) {
    return user.getRoles().stream().map(Role::getCode).anyMatch(ELIGIBLE_ROLE_CODES::contains);
  }

  /**
   * Compact flag rendering for audit details, e.g. {@code [deposit, transfer]}.
   *
   * @param grant the grant
   * @return the active flags as a bracketed list
   */
  @NotNull
  private static String flagString(@NotNull BankAccountGrant grant) {
    StringBuilder sb = new StringBuilder("[");
    if (grant.isCanDeposit()) {
      sb.append("deposit ");
    }
    if (grant.isCanWithdraw()) {
      sb.append("withdraw ");
    }
    if (grant.isCanTransfer()) {
      sb.append("transfer ");
    }
    return sb.toString().trim() + "]";
  }
}
