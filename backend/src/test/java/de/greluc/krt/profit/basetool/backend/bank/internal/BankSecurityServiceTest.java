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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BankSecurityService} over a real {@link BankAccessRules}: the capability
 * matrix (REQ-BANK-009), its independence from org-unit membership (REQ-BANK-008), the holder rule
 * (REQ-BANK-032) and the KRT direct-booking ceiling (REQ-BANK-047).
 */
@ExtendWith(MockitoExtension.class)
class BankSecurityServiceTest {

  @Mock private BankCaller bankCaller;
  @Mock private BankAccountRepository accountRepository;
  @Mock private BankAccountViewGrantRepository viewGrantRepository;
  @Mock private BankAccountApprovalLimitRepository approvalLimitRepository;
  @Mock private BereichRepository bereichRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private BankHolderRepository holderRepository;

  private BankSecurityService bankSecurityService;

  private final UUID accountId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void wireTheAdapterOverTheRealPolicy() {
    BankAccessRules policy =
        new BankAccessRules(
            accountRepository,
            viewGrantRepository,
            approvalLimitRepository,
            bereichRepository,
            orgUnitRepository);
    bankSecurityService = new BankSecurityService(bankCaller, policy, holderRepository);
  }

  @Test
  void canSee_deniesNonBankStaff_evenWithOrgRoles() {
    callerIs(
        TestBankActor.member(userId)
            .seat(UUID.randomUUID(), MembershipRole.STAFFELLEITER)
            .grant(accountId, true, true, true)
            .build());

    assertFalse(bankSecurityService.canSee(accountId));
  }

  @Test
  void canSee_allowsManagementWithoutAnyGrantRow() {
    callerIs(TestBankActor.of(userId).bankManagement().build());

    assertTrue(bankSecurityService.canSee(accountId));
  }

  @Test
  void canSee_allowsEmployeeWithViewOnlyGrantRow() {
    callerIs(employeeWithGrant(false, false, false));

    assertTrue(bankSecurityService.canSee(accountId));
  }

  @Test
  void canSee_deniesEmployeeWithoutGrantRow() {
    callerIs(employeeWithoutGrant());

    assertFalse(bankSecurityService.canSee(accountId));
  }

  @Test
  void capabilityFlags_gateExactlyTheirBooking() {
    callerIs(employeeWithGrant(true, false, false));

    assertTrue(bankSecurityService.canDeposit(accountId));
    assertFalse(bankSecurityService.canWithdraw(accountId));
    assertFalse(bankSecurityService.canTransfer(accountId));
  }

  @Test
  void canWithdraw_and_canTransfer_followTheirFlags() {
    callerIs(employeeWithGrant(false, true, true));

    assertFalse(bankSecurityService.canDeposit(accountId));
    assertTrue(bankSecurityService.canWithdraw(accountId));
    assertTrue(bankSecurityService.canTransfer(accountId));
  }

  @Test
  void capabilities_allowManagementUnrestricted() {
    callerIs(TestBankActor.of(userId).bankManagement().build());

    assertTrue(bankSecurityService.canDeposit(accountId));
    assertTrue(bankSecurityService.canWithdraw(accountId));
    assertTrue(bankSecurityService.canTransfer(accountId));
  }

  /** REQ-BANK-023: confirming a request needs the grant flag of its type. */
  @Test
  void staffRights_canBookNeedsTheFlagOfTheRequestType() {
    callerIs(employeeWithGrant(false, true, false));

    BankStaffRights rights = bankSecurityService.staffRights();

    assertFalse(rights.canBook(BankBookingRequestType.DEPOSIT, accountId));
    assertTrue(rights.canBook(BankBookingRequestType.WITHDRAWAL, accountId));
    assertFalse(rights.canBook(BankBookingRequestType.TRANSFER, accountId));
  }

  @Test
  void staffRights_canBookDeniesEmployeeWithoutGrantRow() {
    callerIs(employeeWithoutGrant());

    assertFalse(
        bankSecurityService.staffRights().canBook(BankBookingRequestType.DEPOSIT, accountId));
  }

  /**
   * The staff rights decide every type and account from the actor's grants alone, with no row read
   * per account (REQ-BANK-023, REQ-DATA-003).
   */
  @Test
  void staffRights_decideEveryTypeAndAccountWithoutReadingRows() {
    UUID otherAccount = UUID.randomUUID();
    callerIs(employeeWithGrant(true, false, false));

    BankStaffRights rights = bankSecurityService.staffRights();

    assertTrue(rights.canBook(BankBookingRequestType.DEPOSIT, accountId));
    assertFalse(rights.canBook(BankBookingRequestType.WITHDRAWAL, accountId));
    assertFalse(rights.canBook(BankBookingRequestType.TRANSFER, accountId));
    assertFalse(rights.canBook(BankBookingRequestType.DEPOSIT, otherAccount));
    verifyNoInteractions(
        accountRepository,
        viewGrantRepository,
        approvalLimitRepository,
        bereichRepository,
        orgUnitRepository,
        holderRepository);
  }

  @Test
  void staffRights_allowManagementOnEveryAccount() {
    callerIs(TestBankActor.of(userId).bankManagement().build());

    assertTrue(
        bankSecurityService.staffRights().canBook(BankBookingRequestType.TRANSFER, accountId));
  }

  @Test
  void staffRights_denyTheUnauthenticatedCaller() {
    callerIs(BankActor.NOBODY);

    assertFalse(
        bankSecurityService.staffRights().canBook(BankBookingRequestType.DEPOSIT, accountId));
  }

  @Test
  void canSee_deniesTheUnauthenticatedCaller() {
    callerIs(BankActor.NOBODY);

    assertFalse(bankSecurityService.canSee(accountId));
  }

  /**
   * Pins the independence contract (REQ-BANK-008): the decision consumes only the actor's roles and
   * grant rows, so a seat on any org unit changes no outcome and no org-unit row is read.
   */
  @Test
  void decision_consultsOnlyRolesAndGrants() {
    BankActor withoutSeat = employeeWithGrant(true, false, true);
    BankActor withSeat =
        TestBankActor.of(userId)
            .roles(Roles.BANK_EMPLOYEE, Roles.KRT_MEMBER)
            .seat(UUID.randomUUID(), MembershipRole.STAFFELLEITER)
            .grant(accountId, true, false, true)
            .build();
    when(bankCaller.actor()).thenReturn(withoutSeat, withSeat);

    BankStaffRights first = bankSecurityService.staffRights();
    BankStaffRights second = bankSecurityService.staffRights();

    for (BankBookingRequestType type : BankBookingRequestType.values()) {
      assertEquals(first.canBook(type, accountId), second.canBook(type, accountId));
    }
    assertEquals(first.canSee(accountId), second.canSee(accountId));
    verifyNoInteractions(
        accountRepository,
        viewGrantRepository,
        approvalLimitRepository,
        bereichRepository,
        orgUnitRepository);
  }

  @Test
  void canSeeHolder_allowsManagementForAnyHolder() {
    callerIs(TestBankActor.of(userId).bankManagement().build());

    assertTrue(bankSecurityService.canSeeHolder(UUID.randomUUID()));
  }

  @Test
  void canSeeHolder_allowsEmployeeForTheirOwnHolder() {
    UUID holderId = UUID.randomUUID();
    callerIs(employeeWithoutGrant());
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holderLinkedTo(userId)));

    assertTrue(bankSecurityService.canSeeHolder(holderId));
  }

  @Test
  void canSeeHolder_deniesEmployeeForSomeoneElsesHolder() {
    UUID holderId = UUID.randomUUID();
    callerIs(employeeWithoutGrant());
    when(holderRepository.findById(holderId))
        .thenReturn(Optional.of(holderLinkedTo(UUID.randomUUID())));

    assertFalse(bankSecurityService.canSeeHolder(holderId));
  }

  @Test
  void canSeeHolder_deniesEmployeeWhenHolderMissing() {
    UUID holderId = UUID.randomUUID();
    callerIs(employeeWithoutGrant());
    when(holderRepository.findById(holderId)).thenReturn(Optional.empty());

    assertFalse(bankSecurityService.canSeeHolder(holderId));
  }

  @Test
  void canSeeHolder_deniesNonBankStaffEvenForTheirOwnHolder() {
    UUID holderId = UUID.randomUUID();
    callerIs(TestBankActor.member(userId).build());
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holderLinkedTo(userId)));

    assertFalse(bankSecurityService.canSeeHolder(holderId));
  }

  @Test
  void canSeeHolder_deniesTheUnauthenticatedCaller() {
    callerIs(BankActor.NOBODY);

    assertFalse(bankSecurityService.canSeeHolder(UUID.randomUUID()));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_plainEmployeeAboveCeiling_returnsTrue() {
    callerIs(employeeWithoutGrant());
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, new BigDecimal("1000"))));

    assertTrue(
        bankSecurityService.exceedsCartelDirectBookingCeiling(accountId, new BigDecimal("1500")));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_atOrBelowCeiling_returnsFalse() {
    callerIs(employeeWithoutGrant());
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, new BigDecimal("1000"))));

    assertFalse(
        bankSecurityService.exceedsCartelDirectBookingCeiling(accountId, new BigDecimal("1000")));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_nullCeilingTreatedAsZero_anyPositiveReturnsTrue() {
    callerIs(employeeWithoutGrant());
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, null)));

    assertTrue(
        bankSecurityService.exceedsCartelDirectBookingCeiling(accountId, new BigDecimal("1")));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_management_returnsFalseEvenAboveCeiling() {
    callerIs(TestBankActor.of(userId).bankManagement().build());

    assertFalse(
        bankSecurityService.exceedsCartelDirectBookingCeiling(
            accountId, new BigDecimal("999999999")));
    verify(accountRepository, never()).findById(any());
  }

  @Test
  void exceedsCartelDirectBookingCeiling_nonCartelAccount_returnsFalse() {
    callerIs(employeeWithoutGrant());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(areaAccount(accountId)));

    assertFalse(
        bankSecurityService.exceedsCartelDirectBookingCeiling(
            accountId, new BigDecimal("999999999")));
  }

  /**
   * Makes the actor the caller of every decision in the test.
   *
   * @param actor the request's actor
   */
  private void callerIs(BankActor actor) {
    when(bankCaller.actor()).thenReturn(actor);
  }

  /**
   * An employee (not management) holding a grant on the test account with the given flags.
   *
   * @param deposit {@code can_deposit}
   * @param withdraw {@code can_withdraw}
   * @param transfer {@code can_transfer}
   * @return the actor
   */
  private BankActor employeeWithGrant(boolean deposit, boolean withdraw, boolean transfer) {
    return TestBankActor.of(userId)
        .roles(Roles.BANK_EMPLOYEE)
        .grant(accountId, deposit, withdraw, transfer)
        .build();
  }

  /**
   * An employee (not management) without any grant row.
   *
   * @return the actor
   */
  private BankActor employeeWithoutGrant() {
    return TestBankActor.of(userId).roles(Roles.BANK_EMPLOYEE).build();
  }

  /**
   * Builds a holder row linked to the given user id, the self-ownership input of {@code
   * canSeeHolder}.
   *
   * @param ownerUserId the member the holder belongs to
   * @return the holder
   */
  private static BankHolder holderLinkedTo(UUID ownerUserId) {
    User user = new User();
    user.setId(ownerUserId);
    BankHolder holder = new BankHolder();
    holder.setUser(user);
    return holder;
  }

  /**
   * Builds an active {@code CARTEL} account with the given direct-booking ceiling.
   *
   * @param id the account id the lookup returns it for
   * @param ceiling the T1 employee-approval ceiling, or {@code null} for an unset ceiling
   * @return the CARTEL account
   */
  private static BankAccount cartelAccount(UUID id, @Nullable BigDecimal ceiling) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo("KB-CART");
    account.setName("KRT");
    account.setType(BankAccountType.CARTEL);
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setEmployeeApprovalCeiling(ceiling);
    return account;
  }

  /**
   * Builds an active {@code AREA} account, a non-CARTEL type.
   *
   * @param id the account id the lookup returns it for
   * @return the AREA account
   */
  private static BankAccount areaAccount(UUID id) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo("KB-" + id.toString().substring(0, 4));
    account.setName("Bereichskonto");
    account.setType(BankAccountType.AREA);
    account.setAreaName("Profit");
    account.setStatus(BankAccountStatus.ACTIVE);
    return account;
  }
}
