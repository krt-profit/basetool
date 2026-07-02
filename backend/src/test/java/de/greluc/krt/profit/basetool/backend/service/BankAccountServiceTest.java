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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.mapper.BankAccountMapper;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.BankCapabilitiesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankAccountLifecycleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankAccountRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankGrantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RenameBankAccountRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link BankAccountService}: the type-specific owner-reference validation, the
 * singleton/per-org-unit uniqueness 409s, the {@code KB-} number generation, the zero-balance close
 * rule and the explicit optimistic-lock checks (REQ-BANK-001/-002).
 */
@ExtendWith(MockitoExtension.class)
class BankAccountServiceTest {

  @Mock private BankAccountRepository accountRepository;
  @Mock private BankPostingRepository postingRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private BankAccountMapper bankAccountMapper;
  @Mock private BankAuditService bankAuditService;
  @Mock private BankBookingRequestService bankBookingRequestService;
  @Mock private BankGrantService bankGrantService;
  @Mock private BankApprovalLimitService bankApprovalLimitService;

  @InjectMocks private BankAccountService bankAccountService;

  @Test
  void createAccount_orgUnit_requiresReferenceAndUniqueness() {
    // Given
    UUID orgUnitId = UUID.randomUUID();
    Squadron squadron = new Squadron();
    squadron.setId(orgUnitId);
    when(orgUnitRepository.findById(orgUnitId)).thenReturn(Optional.of(squadron));
    when(accountRepository.existsByOrgUnitId(orgUnitId)).thenReturn(false);
    when(accountRepository.nextAccountNoValue()).thenReturn(7L);
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When
    createAsManagement(
        new CreateBankAccountRequest("Staffel IRIDIUM", BankAccountType.ORG_UNIT, orgUnitId, null));

    // Then
    ArgumentCaptor<BankAccount> saved = ArgumentCaptor.forClass(BankAccount.class);
    verify(accountRepository).save(saved.capture());
    assertEquals("KB-0007", saved.getValue().getAccountNo());
    assertEquals(BankAccountStatus.ACTIVE, saved.getValue().getStatus());
    verify(bankAuditService)
        .record(eq(BankAuditEventType.ACCOUNT_CREATED), any(), any(), any(), any());
  }

  @Test
  void createAccount_orgUnit_withoutReferenceIsBadRequest() {
    // When / Then
    assertThrows(
        BadRequestException.class,
        () ->
            createAsManagement(
                new CreateBankAccountRequest("X", BankAccountType.ORG_UNIT, null, null)));
    verify(accountRepository, never()).save(any());
  }

  @Test
  void createAccount_orgUnit_rejectsBereichOrOlKind() {
    // Epic #692 Phase 6 (REQ-ORG-019): an ORG_UNIT account must reference a Staffel/SK — a Bereich
    // belongs to an AREA account and the OL to the CARTEL account. Without this guard the ORG_UNIT
    // account would consume the Bereich/OL's one-account slot (symmetric with the AREA/CARTEL
    // guards).
    UUID bereichId = UUID.randomUUID();
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    when(orgUnitRepository.findById(bereichId)).thenReturn(Optional.of(bereich));

    assertThrows(
        BadRequestException.class,
        () ->
            createAsManagement(
                new CreateBankAccountRequest("X", BankAccountType.ORG_UNIT, bereichId, null)));
    verify(accountRepository, never()).save(any());
  }

  @Test
  void createAccount_secondAccountForSameOrgUnitIsRejected() {
    // Given
    UUID orgUnitId = UUID.randomUUID();
    Squadron squadron = new Squadron();
    squadron.setId(orgUnitId);
    when(orgUnitRepository.findById(orgUnitId)).thenReturn(Optional.of(squadron));
    when(accountRepository.existsByOrgUnitId(orgUnitId)).thenReturn(true);

    // When / Then
    assertThrows(
        DuplicateEntityException.class,
        () ->
            createAsManagement(
                new CreateBankAccountRequest("X", BankAccountType.ORG_UNIT, orgUnitId, null)));
  }

  @Test
  void createAccount_secondCartelSingletonIsRejected() {
    // Given
    when(accountRepository.existsByType(BankAccountType.CARTEL)).thenReturn(true);

    // When / Then
    assertThrows(
        DuplicateEntityException.class,
        () ->
            createAsManagement(
                new CreateBankAccountRequest("DAS KARTELL", BankAccountType.CARTEL, null, null)));
  }

  @Test
  void createAccount_area_requiresBereichFkAndForbidsAreaNameAndNonBereich() {
    // Epic #692 (REQ-ORG-019): an AREA account is owned by its Bereich via the org_unit FK.
    // Missing org unit → 400.
    assertThrows(
        BadRequestException.class,
        () ->
            createAsManagement(
                new CreateBankAccountRequest("Bereich Profit", BankAccountType.AREA, null, null)));
    // A free-form area name is no longer accepted on creation → 400 (checked before the FK lookup).
    assertThrows(
        BadRequestException.class,
        () ->
            createAsManagement(
                new CreateBankAccountRequest(
                    "Bereich Profit", BankAccountType.AREA, UUID.randomUUID(), "Profit")));
    // The referenced org unit must be a Bereich, not a Staffel/SK → 400.
    UUID staffelId = UUID.randomUUID();
    Squadron staffel = new Squadron();
    staffel.setId(staffelId);
    when(orgUnitRepository.findById(staffelId)).thenReturn(Optional.of(staffel));
    assertThrows(
        BadRequestException.class,
        () ->
            createAsManagement(
                new CreateBankAccountRequest(
                    "Bereich Profit", BankAccountType.AREA, staffelId, null)));
    verify(accountRepository, never()).save(any());
  }

  @Test
  void createAccount_area_linksBereichAndRejectsDuplicate() {
    // Given a Bereich that owns no account yet
    UUID bereichId = UUID.randomUUID();
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    when(orgUnitRepository.findById(bereichId)).thenReturn(Optional.of(bereich));
    when(accountRepository.existsByOrgUnitId(bereichId)).thenReturn(false);
    when(accountRepository.nextAccountNoValue()).thenReturn(9L);
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When
    createAsManagement(
        new CreateBankAccountRequest("Bereich Profit", BankAccountType.AREA, bereichId, null));

    // Then the account is linked to the Bereich via the org_unit FK (not the area name)
    ArgumentCaptor<BankAccount> saved = ArgumentCaptor.forClass(BankAccount.class);
    verify(accountRepository).save(saved.capture());
    assertEquals(bereichId, saved.getValue().getOrgUnit().getId());
    assertEquals(null, saved.getValue().getAreaName());

    // And a second account for the same Bereich is rejected (one account per org unit)
    when(accountRepository.existsByOrgUnitId(bereichId)).thenReturn(true);
    assertThrows(
        DuplicateEntityException.class,
        () ->
            createAsManagement(
                new CreateBankAccountRequest(
                    "Bereich Profit 2", BankAccountType.AREA, bereichId, null)));
  }

  @Test
  void createAccount_cartel_linksOrganisationsleitungAndRejectsNonOl() {
    // The singleton CARTEL account maps to the Organisationsleitung via the org_unit FK.
    UUID olId = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    when(accountRepository.existsByType(BankAccountType.CARTEL)).thenReturn(false);
    when(orgUnitRepository.findById(olId)).thenReturn(Optional.of(ol));
    when(accountRepository.existsByOrgUnitId(olId)).thenReturn(false);
    when(accountRepository.nextAccountNoValue()).thenReturn(1L);
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    createAsManagement(
        new CreateBankAccountRequest("DAS KARTELL", BankAccountType.CARTEL, olId, null));

    ArgumentCaptor<BankAccount> saved = ArgumentCaptor.forClass(BankAccount.class);
    verify(accountRepository).save(saved.capture());
    assertEquals(olId, saved.getValue().getOrgUnit().getId());

    // A non-OL org unit on the CARTEL account is rejected.
    UUID staffelId = UUID.randomUUID();
    Squadron staffel = new Squadron();
    staffel.setId(staffelId);
    when(accountRepository.existsByType(BankAccountType.CARTEL)).thenReturn(false);
    when(orgUnitRepository.findById(staffelId)).thenReturn(Optional.of(staffel));
    assertThrows(
        BadRequestException.class,
        () ->
            createAsManagement(
                new CreateBankAccountRequest(
                    "DAS KARTELL", BankAccountType.CARTEL, staffelId, null)));
  }

  @Test
  void closeAccount_rejectsNonZeroBalanceWithStableCode() {
    // Given
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 3L);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(postingRepository.accountBalance(accountId)).thenReturn(new BigDecimal("120"));

    // When
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankAccountService.closeAccount(accountId, new BankAccountLifecycleRequest(3L)));

    // Then
    assertEquals(BankConflictException.CODE_BANK_ACCOUNT_NOT_EMPTY, ex.getCode());
    verify(accountRepository, never()).save(any());
  }

  @Test
  void closeAccount_rejectsOpenPendingRequestsWithStableCode() {
    // Given a zero-balance account that still has an open pending booking request (REQ-BANK-025)
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 2L);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(postingRepository.accountBalance(accountId)).thenReturn(BigDecimal.ZERO);
    when(bankBookingRequestService.hasOpenRequests(accountId)).thenReturn(true);

    // When
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> bankAccountService.closeAccount(accountId, new BankAccountLifecycleRequest(2L)));

    // Then
    assertEquals(BankConflictException.CODE_BANK_ACCOUNT_HAS_PENDING_REQUESTS, ex.getCode());
    verify(accountRepository, never()).save(any());
  }

  @Test
  void closeAccount_staleVersionFailsFastWith409() {
    // Given
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 5L);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

    // When / Then
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> bankAccountService.closeAccount(accountId, new BankAccountLifecycleRequest(4L)));
    verify(postingRepository, never()).accountBalance(any());
  }

  @Test
  void renameAccount_auditsOldAndNewName() {
    // Given
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 1L);
    account.setName("Alt");
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(postingRepository.accountBalance(accountId)).thenReturn(BigDecimal.ZERO);

    // When
    bankAccountService.renameAccount(accountId, new RenameBankAccountRequest("Neu", 1L));

    // Then
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.ACCOUNT_RENAMED),
            eq(accountId),
            any(),
            any(),
            eq("'Alt' -> 'Neu'"));
  }

  @Test
  void reopenAccount_restoresActiveStatusAndAuditsReopen() {
    // Given: a closed account
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 2L);
    account.setStatus(BankAccountStatus.CLOSED);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(postingRepository.accountBalance(accountId)).thenReturn(BigDecimal.ZERO);

    // When
    bankAccountService.reopenAccount(accountId, new BankAccountLifecycleRequest(2L));

    // Then: status flips back to ACTIVE and the reopen is audited (REQ-BANK-002)
    ArgumentCaptor<BankAccount> saved = ArgumentCaptor.forClass(BankAccount.class);
    verify(accountRepository).save(saved.capture());
    assertEquals(BankAccountStatus.ACTIVE, saved.getValue().getStatus());
    verify(bankAuditService)
        .record(eq(BankAuditEventType.ACCOUNT_REOPENED), eq(accountId), any(), any(), any());
  }

  @Test
  void reopenAccount_staleVersionFailsFastWith409() {
    // Given
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 5L);
    account.setStatus(BankAccountStatus.CLOSED);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

    // When / Then
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> bankAccountService.reopenAccount(accountId, new BankAccountLifecycleRequest(4L)));
    verify(accountRepository, never()).save(any());
  }

  @Test
  void createAccount_employeeMayCreateSpecialAndIsAutoGranted() {
    // Given: a bank employee (management = false) with a creator id
    UUID creatorId = UUID.randomUUID();
    when(accountRepository.nextAccountNoValue()).thenReturn(42L);
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When: the employee creates a SPECIAL account
    bankAccountService.createAccount(
        new CreateBankAccountRequest("Sonderkonto Logistik", BankAccountType.SPECIAL, null, null),
        false,
        creatorId);

    // Then: the account is created and the creator is auto-granted full capability (REQ-BANK-030)
    verify(accountRepository).save(any(BankAccount.class));
    ArgumentCaptor<CreateBankGrantRequest> grant =
        ArgumentCaptor.forClass(CreateBankGrantRequest.class);
    verify(bankGrantService).createGrant(grant.capture());
    assertEquals(creatorId, grant.getValue().userId());
    assertTrue(
        grant.getValue().canDeposit()
            && grant.getValue().canWithdraw()
            && grant.getValue().canTransfer(),
        "the creator gets full capability on their special account");
  }

  @Test
  void createAccount_employeeMayNotCreateNonSpecialType() {
    // When / Then: a bank employee creating any non-SPECIAL type is denied (REQ-BANK-030, ADR-0040)
    UUID creatorId = UUID.randomUUID();
    assertThrows(
        AccessDeniedException.class,
        () ->
            bankAccountService.createAccount(
                new CreateBankAccountRequest(
                    "Staffel X", BankAccountType.ORG_UNIT, UUID.randomUUID(), null),
                false,
                creatorId));
    verify(accountRepository, never()).save(any());
    verify(bankGrantService, never()).createGrant(any());
  }

  @Test
  void setBalanceTarget_setsTargetAndAuditsSet() {
    // REQ-BANK-036: bank staff with access set the balance target; audited as SET.
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 2L);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(postingRepository.accountBalance(accountId)).thenReturn(BigDecimal.ZERO);

    bankAccountService.setBalanceTarget(accountId, new BigDecimal("5000"), 2L);

    ArgumentCaptor<BankAccount> saved = ArgumentCaptor.forClass(BankAccount.class);
    verify(accountRepository).save(saved.capture());
    assertEquals(0, new BigDecimal("5000").compareTo(saved.getValue().getBalanceTarget()));
    verify(bankAuditService)
        .record(eq(BankAuditEventType.BALANCE_TARGET_SET), eq(accountId), any(), any(), any());
  }

  @Test
  void setBalanceTarget_nullClearsTargetAndAuditsCleared() {
    // REQ-BANK-036: a null target clears the goal; audited as CLEARED.
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 1L);
    account.setBalanceTarget(new BigDecimal("9000"));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(postingRepository.accountBalance(accountId)).thenReturn(BigDecimal.ZERO);

    bankAccountService.setBalanceTarget(accountId, null, 1L);

    ArgumentCaptor<BankAccount> saved = ArgumentCaptor.forClass(BankAccount.class);
    verify(accountRepository).save(saved.capture());
    assertEquals(null, saved.getValue().getBalanceTarget());
    verify(bankAuditService)
        .record(eq(BankAuditEventType.BALANCE_TARGET_CLEARED), eq(accountId), any(), any(), any());
  }

  @Test
  void setBalanceTarget_staleVersionFailsFastWith409() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 5L);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> bankAccountService.setBalanceTarget(accountId, new BigDecimal("1"), 4L));
    verify(accountRepository, never()).save(any());
  }

  @Test
  void getAccountDetail_assemblesApprovalLimitsReadOnlyEvenForManagement() {
    // REQ-BANK-041: the bank-staff account-detail surface never edits approval limits. Even a
    // management caller gets the read-only variant (assemble called with canEdit == false) — limits
    // are set/cleared exclusively in the org-unit bank settings, so this surface only displays
    // them.
    UUID accountId = UUID.randomUUID();
    BankAccount account = accountWithVersion(accountId, 1L);
    account.setType(BankAccountType.ORG_UNIT);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

    bankAccountService.getAccountDetail(
        accountId, new BankCapabilitiesDto(false, false, false, true));

    verify(bankApprovalLimitService).assemble(account, false);
  }

  @Test
  void setCartelApprovalTiers_setsCeilingsAndAuditsSet() {
    // REQ-BANK-047: the Bankleitung sets the KRT ladder thresholds; audited as SET.
    UUID accountId = UUID.randomUUID();
    BankAccount cartel = accountWithVersion(accountId, 2L);
    cartel.setType(BankAccountType.CARTEL);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(cartel));
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(postingRepository.accountBalance(accountId)).thenReturn(BigDecimal.ZERO);

    bankAccountService.setCartelApprovalTiers(
        accountId, new BigDecimal("1000"), new BigDecimal("5000"), 2L);

    ArgumentCaptor<BankAccount> saved = ArgumentCaptor.forClass(BankAccount.class);
    verify(accountRepository).save(saved.capture());
    assertEquals(
        0, new BigDecimal("1000").compareTo(saved.getValue().getEmployeeApprovalCeiling()));
    assertEquals(
        0, new BigDecimal("5000").compareTo(saved.getValue().getAreaLeadApprovalCeiling()));
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.CARTEL_APPROVAL_TIERS_SET), eq(accountId), any(), any(), any());
  }

  @Test
  void setCartelApprovalTiers_bothNullClearsAndAuditsCleared() {
    // REQ-BANK-047: clearing both thresholds is audited as CLEARED.
    UUID accountId = UUID.randomUUID();
    BankAccount cartel = accountWithVersion(accountId, 1L);
    cartel.setType(BankAccountType.CARTEL);
    cartel.setEmployeeApprovalCeiling(new BigDecimal("1000"));
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(cartel));
    when(accountRepository.save(any(BankAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(postingRepository.accountBalance(accountId)).thenReturn(BigDecimal.ZERO);

    bankAccountService.setCartelApprovalTiers(accountId, null, null, 1L);

    ArgumentCaptor<BankAccount> saved = ArgumentCaptor.forClass(BankAccount.class);
    verify(accountRepository).save(saved.capture());
    assertEquals(null, saved.getValue().getEmployeeApprovalCeiling());
    assertEquals(null, saved.getValue().getAreaLeadApprovalCeiling());
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.CARTEL_APPROVAL_TIERS_CLEARED),
            eq(accountId),
            any(),
            any(),
            any());
  }

  @Test
  void setCartelApprovalTiers_nonCartelAccount_rejectedBeforeSave() {
    // REQ-BANK-047: only the KRT (CARTEL) account carries thresholds.
    UUID accountId = UUID.randomUUID();
    BankAccount special = accountWithVersion(accountId, 1L); // SPECIAL by default
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(special));

    assertThrows(
        BadRequestException.class,
        () ->
            bankAccountService.setCartelApprovalTiers(
                accountId, new BigDecimal("1000"), new BigDecimal("5000"), 1L));
    verify(accountRepository, never()).save(any());
  }

  @Test
  void setCartelApprovalTiers_t2BelowT1_rejectedBeforeSave() {
    // REQ-BANK-047: the area-lead ceiling must be at or above the bank-employee ceiling.
    UUID accountId = UUID.randomUUID();
    BankAccount cartel = accountWithVersion(accountId, 1L);
    cartel.setType(BankAccountType.CARTEL);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(cartel));

    assertThrows(
        BadRequestException.class,
        () ->
            bankAccountService.setCartelApprovalTiers(
                accountId, new BigDecimal("5000"), new BigDecimal("1000"), 1L));
    verify(accountRepository, never()).save(any());
  }

  @Test
  void setCartelApprovalTiers_staleVersionFailsFastWith409() {
    UUID accountId = UUID.randomUUID();
    BankAccount cartel = accountWithVersion(accountId, 5L);
    cartel.setType(BankAccountType.CARTEL);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(cartel));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () ->
            bankAccountService.setCartelApprovalTiers(
                accountId, new BigDecimal("1000"), new BigDecimal("5000"), 4L));
    verify(accountRepository, never()).save(any());
  }

  /** Creates an account in the management perspective (any type, no auto-grant). */
  private void createAsManagement(CreateBankAccountRequest request) {
    bankAccountService.createAccount(request, true, null);
  }

  /** Builds a SPECIAL account stub with a given version (reflection-free, via setters). */
  private static BankAccount accountWithVersion(UUID id, Long version) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo("KB-0001");
    account.setName("Konto");
    account.setType(BankAccountType.SPECIAL);
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setVersion(version);
    return account;
  }
}
