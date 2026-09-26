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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.mapper.BankGrantMapper;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrantId;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link BankGrantService}: grantee eligibility via the synced Bank Employee role
 * (REQ-BANK-009; org-unit membership is irrelevant in both directions, REQ-BANK-008), duplicate
 * pre-check, optimistic-lock check, and the exactly-one-audit-row-per-mutation rule with
 * before/after flags (REQ-BANK-012).
 */
@ExtendWith(MockitoExtension.class)
class BankGrantServiceTest {

  @Mock private BankAccountGrantRepository grantRepository;
  @Mock private BankAccountRepository accountRepository;
  @Mock private UserRepository userRepository;
  @Mock private BankGrantMapper bankGrantMapper;
  @Mock private BankAuditService bankAuditService;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private BankGrantService bankGrantService;

  private final UUID userId = UUID.randomUUID();
  private final UUID accountId = UUID.randomUUID();

  @Test
  void createGrant_rejectsGranteeWithoutBankRole() {
    User user = userWithRoleCodes("KRT_MEMBER", "OFFICER");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account()));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankGrantService.createGrant(
                    new CreateBankGrantRequest(userId, accountId, true, false, false)));

    assertEquals(BankConflictException.CODE_BANK_GRANTEE_MISSING_ROLE, ex.getCode());
    verify(grantRepository, never()).save(any());
  }

  @Test
  void createGrant_acceptsBankEmployeeAndAudits() {
    User user = userWithRoleCodes("BANK_EMPLOYEE");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account()));
    when(grantRepository.existsById(new BankAccountGrantId(userId, accountId))).thenReturn(false);
    when(grantRepository.save(any(BankAccountGrant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    bankGrantService.createGrant(new CreateBankGrantRequest(userId, accountId, true, false, true));

    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.GRANT_CREATED),
            eq(accountId),
            any(),
            eq(userId),
            eq("[deposit transfer]"));
  }

  @Test
  void createGrant_rejectsDuplicate() {
    User user = userWithRoleCodes("BANK_EMPLOYEE");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account()));
    when(grantRepository.existsById(new BankAccountGrantId(userId, accountId))).thenReturn(true);

    assertThrows(
        DuplicateEntityException.class,
        () ->
            bankGrantService.createGrant(
                new CreateBankGrantRequest(userId, accountId, false, false, false)));
  }

  @Test
  void updateGrant_auditsBeforeAndAfterFlags() {
    BankAccountGrant grant = grantWithFlags(true, false, false, 2L);
    when(grantRepository.findById(new BankAccountGrantId(userId, accountId)))
        .thenReturn(Optional.of(grant));
    when(grantRepository.save(any(BankAccountGrant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    bankGrantService.updateGrant(
        userId, accountId, new UpdateBankGrantRequest(false, true, false, 2L));

    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.GRANT_UPDATED),
            eq(accountId),
            any(),
            eq(userId),
            eq("[deposit] -> [withdraw]"));
  }

  @Test
  void updateGrant_staleVersionFailsFastWith409() {
    BankAccountGrant grant = grantWithFlags(false, false, false, 7L);
    when(grantRepository.findById(new BankAccountGrantId(userId, accountId)))
        .thenReturn(Optional.of(grant));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () ->
            bankGrantService.updateGrant(
                userId, accountId, new UpdateBankGrantRequest(true, true, true, 6L)));
    verify(grantRepository, never()).save(any());
  }

  @Test
  void deleteGrant_auditsRevocationWithFinalFlags() {
    BankAccountGrant grant = grantWithFlags(true, true, false, 1L);
    when(grantRepository.findById(new BankAccountGrantId(userId, accountId)))
        .thenReturn(Optional.of(grant));

    bankGrantService.deleteGrant(userId, accountId);

    verify(grantRepository).delete(grant);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.GRANT_REVOKED),
            eq(accountId),
            any(),
            eq(userId),
            contains("deposit"));
  }

  /** Builds a user whose synced roles carry the given codes. */
  private User userWithRoleCodes(String... codes) {
    User user = new User();
    user.setId(userId);
    user.setUsername("grantee");
    java.util.Set<Role> roles = new java.util.HashSet<>();
    for (String code : codes) {
      Role role = new Role();
      role.setCode(code);
      role.setName(code);
      role.setPermissions(Set.of());
      roles.add(role);
    }
    user.setRoles(roles);
    return user;
  }

  private BankAccount account() {
    BankAccount account = new BankAccount();
    account.setId(accountId);
    account.setAccountNo("KB-0001");
    account.setName("Konto");
    account.setType(BankAccountType.SPECIAL);
    return account;
  }

  /** Builds a grant stub with user and account references populated for mapping/audit. */
  private BankAccountGrant grantWithFlags(
      boolean deposit, boolean withdraw, boolean transfer, Long version) {
    BankAccountGrant grant = new BankAccountGrant();
    grant.setId(new BankAccountGrantId(userId, accountId));
    User user = userWithRoleCodes("BANK_EMPLOYEE");
    grant.setUser(user);
    grant.setAccount(account());
    grant.setCanDeposit(deposit);
    grant.setCanWithdraw(withdraw);
    grant.setCanTransfer(transfer);
    grant.setVersion(version);
    lenient()
        .when(bankGrantMapper.toDto(any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(null);
    return grant;
  }
}
