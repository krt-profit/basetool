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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccessPolicy;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountAccess;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountRight;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The four queries of {@link BankAccessPolicy} (ADR-0352), asked for explicit actors over mocked
 * bank rows: visibility under the pin, the viewable list, request admission with its routing, and
 * the staff rights.
 */
@ExtendWith(MockitoExtension.class)
class BankAccessRulesTest {

  private static final BigDecimal T1 = new BigDecimal("1000");
  private static final BigDecimal T2 = new BigDecimal("10000");

  @Mock private BankAccountRepository accountRepository;
  @Mock private BankAccountViewGrantRepository viewGrantRepository;
  @Mock private BankAccountApprovalLimitRepository approvalLimitRepository;
  @Mock private BereichRepository bereichRepository;
  @Mock private OrgUnitRepository orgUnitRepository;

  private BankAccessRules policy;
  private final UUID staffelId = UUID.randomUUID();
  private final UUID otherStaffelId = UUID.randomUUID();
  private BankAccount staffelAccount;
  private BankAccount cartelAccount;

  @BeforeEach
  void setUp() {
    policy =
        new BankAccessRules(
            accountRepository,
            viewGrantRepository,
            approvalLimitRepository,
            bereichRepository,
            orgUnitRepository);
    staffelAccount =
        account(BankAccountType.ORG_UNIT, staffel(staffelId), BankAccountStatus.ACTIVE);
    cartelAccount = account(BankAccountType.CARTEL, null, BankAccountStatus.ACTIVE);
    cartelAccount.setEmployeeApprovalCeiling(T1);
    cartelAccount.setAreaLeadApprovalCeiling(T2);
    lenient().when(viewGrantRepository.findByAccountId(any())).thenReturn(List.of());
    lenient().when(approvalLimitRepository.findByAccountId(any())).thenReturn(List.of());
  }

  @Test
  void accessTo_unknownAccount_isHiddenLikeAForbiddenOne() {
    when(accountRepository.findById(any())).thenReturn(Optional.empty());

    assertThat(
            policy.accessTo(
                TestBankActor.of(UUID.randomUUID()).admin().build(), UUID.randomUUID(), null))
        .isSameAs(BankAccountAccess.Hidden.INSTANCE);
  }

  @Test
  void accessTo_accountWithoutAnyRight_isHidden() {
    stubFind(staffelAccount);
    BankActor outsider = TestBankActor.member(UUID.randomUUID()).build();

    assertThat(policy.accessTo(outsider, staffelAccount.getId(), null))
        .isSameAs(BankAccountAccess.Hidden.INSTANCE);
  }

  @Test
  void accessTo_staffelleiter_viewsRequestsAndHoldsTheOwnAccount() {
    stubFind(staffelAccount);
    BankActor lead =
        TestBankActor.member(UUID.randomUUID())
            .seat(staffelId, MembershipRole.STAFFELLEITER)
            .build();

    BankAccountAccess access = policy.accessTo(lead, staffelAccount.getId(), null);

    assertThat(access).isInstanceOf(BankAccountAccess.Visible.class);
    BankAccountAccess.Visible visible = (BankAccountAccess.Visible) access;
    assertThat(visible.rights())
        .containsExactlyInAnyOrder(
            BankAccountRight.VIEW,
            BankAccountRight.REQUEST,
            BankAccountRight.RESPONSIBLE_HOLDER,
            BankAccountRight.SET_TARGET,
            BankAccountRight.CONFIGURE_VISIBILITY,
            BankAccountRight.CONFIGURE_APPROVAL_LIMITS);
    assertThat(visible.approvalExempt()).isTrue();
    assertThat(visible.approvalLimit()).isNull();
  }

  @Test
  void accessTo_pinOnAnotherOverseenUnit_narrowsTheViewWithoutTheCascade() {
    stubFind(staffelAccount);
    BankActor bereichsleiter =
        TestBankActor.member(UUID.randomUUID()).reach(staffelId, otherStaffelId).build();

    assertThat(
            policy
                .accessTo(bereichsleiter, staffelAccount.getId(), null)
                .has(BankAccountRight.VIEW))
        .isTrue();
    assertThat(
            policy
                .accessTo(bereichsleiter, staffelAccount.getId(), otherStaffelId)
                .has(BankAccountRight.VIEW))
        .isFalse();
  }

  @Test
  void accessTo_pinOutsideTheReach_isIgnored() {
    stubFind(staffelAccount);
    BankActor overseer = TestBankActor.member(UUID.randomUUID()).reach(staffelId).build();

    assertThat(
            policy
                .accessTo(overseer, staffelAccount.getId(), UUID.randomUUID())
                .has(BankAccountRight.VIEW))
        .isTrue();
  }

  @Test
  void accessTo_seatsWithoutKrtMember_overseeAndHoldNothing() {
    stubFind(staffelAccount);
    BankActor formerMember =
        TestBankActor.of(UUID.randomUUID())
            .roles("BANK_EMPLOYEE")
            .seat(staffelId, MembershipRole.STAFFELLEITER)
            .reach(staffelId)
            .build();

    assertThat(policy.accessTo(formerMember, staffelAccount.getId(), null))
        .isSameAs(BankAccountAccess.Hidden.INSTANCE);
  }

  @Test
  void accessTo_olSeatWithoutKrtMember_holdsNeitherTheKrtAccountNorASonderkonto() {
    BankAccount special = account(BankAccountType.SPECIAL, null, BankAccountStatus.ACTIVE);
    BankActor formerOl =
        TestBankActor.of(UUID.randomUUID())
            .roles("BANK_EMPLOYEE")
            .seat(staffelId, MembershipRole.OL_MEMBER)
            .build();

    stubFind(cartelAccount);
    assertThat(policy.accessTo(formerOl, cartelAccount.getId(), null))
        .isSameAs(BankAccountAccess.Hidden.INSTANCE);
    stubFind(special);
    assertThat(policy.accessTo(formerOl, special.getId(), null))
        .isSameAs(BankAccountAccess.Hidden.INSTANCE);
  }

  @Test
  void staffRights_olSeatWithoutKrtMember_approvesNoTopBandRequest() {
    BankActor formerOl =
        TestBankActor.of(UUID.randomUUID())
            .roles("BANK_EMPLOYEE")
            .seat(staffelId, MembershipRole.OL_MEMBER)
            .build();

    BankStaffRights rights = policy.staffRights(formerOl);

    assertThat(rights.mayApprove(BankRequestApprover.ORGANISATIONSLEITUNG, false)).isFalse();
  }

  @Test
  void admit_seatWithoutKrtMember_isNoApprovalExemptHolder() {
    BankActor formerLead =
        TestBankActor.of(UUID.randomUUID())
            .roles("BANK_EMPLOYEE")
            .seat(staffelId, MembershipRole.STAFFELLEITER)
            .build();

    BankBookingRequest request = new BankBookingRequest();
    request.setAccount(staffelAccount);
    request.setType(BankBookingRequestType.WITHDRAWAL);

    BankAdmission.Admitted routing =
        policy.routeCorrection(formerLead, request, new BigDecimal("50"));

    assertThat(routing.requiresApproval()).isTrue();
    assertThat(routing.requiredApprover()).isEqualTo(BankRequestApprover.RESPONSIBLE_HOLDER);
  }

  @Test
  void approves_decidesForTheRequestsAccountWithoutReadingEveryAccount() {
    BankBookingRequest request = new BankBookingRequest();
    request.setAccount(staffelAccount);
    request.setRequestedBy(UUID.randomUUID());
    request.setRequiredApprover(BankRequestApprover.RESPONSIBLE_HOLDER);
    BankActor lead =
        TestBankActor.member(UUID.randomUUID())
            .seat(staffelId, MembershipRole.STAFFELLEITER)
            .build();
    BankActor member = TestBankActor.member(UUID.randomUUID()).build();

    assertThat(policy.approves(lead, request)).isTrue();
    assertThat(policy.approves(member, request)).isFalse();
    assertThat(
            policy.approves(
                TestBankActor.member(request.getRequestedBy())
                    .seat(staffelId, MembershipRole.STAFFELLEITER)
                    .build(),
                request))
        .isFalse();
    verify(accountRepository, never()).findAllByOrderByAccountNoAsc();
  }

  @Test
  void accessTo_krtAccount_readsNoApprovalLimitRows() {
    stubFind(cartelAccount);

    policy.accessTo(TestBankActor.member(UUID.randomUUID()).build(), cartelAccount.getId(), null);

    verify(approvalLimitRepository, never()).findByAccountId(any());
  }

  @Test
  void canView_readsTheViewRuleOnlyAndNoApprovalLimitRows() {
    BankActor member =
        TestBankActor.member(UUID.randomUUID()).seat(staffelId, MembershipRole.MEMBER).build();
    BankAccountViewGrant allMembers = new BankAccountViewGrant();
    allMembers.setGranteeKind(BankAccountViewGranteeKind.ALL_MEMBERS);
    when(viewGrantRepository.findByAccountId(staffelAccount.getId()))
        .thenReturn(List.of(allMembers));

    assertThat(policy.canView(member, staffelAccount, null)).isTrue();
    assertThat(policy.canView(TestBankActor.member(UUID.randomUUID()).build(), staffelAccount, null))
        .isFalse();
    verify(approvalLimitRepository, never()).findByAccountId(any());
  }

  @Test
  void accessTo_grantedEmployee_seesOnTheStaffSideOnly() {
    stubFind(staffelAccount);
    BankActor employee =
        TestBankActor.of(UUID.randomUUID())
            .roles("BANK_EMPLOYEE")
            .grant(staffelAccount.getId(), true, false, false)
            .build();

    BankAccountAccess.Visible visible =
        (BankAccountAccess.Visible) policy.accessTo(employee, staffelAccount.getId(), null);

    assertThat(visible.rights())
        .containsExactlyInAnyOrder(BankAccountRight.STAFF_VIEW, BankAccountRight.STAFF_DEPOSIT);
    assertThat(visible.disclosedOnOrgUnitSide()).isFalse();
  }

  @Test
  void accessTo_plainMember_seesTheKrtAccountWithItsT1Ceiling() {
    stubFind(cartelAccount);

    BankAccountAccess.Visible visible =
        (BankAccountAccess.Visible)
            policy.accessTo(
                TestBankActor.member(UUID.randomUUID()).build(), cartelAccount.getId(), null);

    assertThat(visible.rights())
        .containsExactlyInAnyOrder(BankAccountRight.VIEW, BankAccountRight.REQUEST);
    assertThat(visible.approvalLimit()).isEqualByComparingTo(T1);
  }

  @Test
  void viewableAccounts_listsOnlyActiveViewableAccountsInOrder() {
    BankAccount closed =
        account(BankAccountType.ORG_UNIT, staffel(staffelId), BankAccountStatus.CLOSED);
    BankAccount foreign =
        account(BankAccountType.ORG_UNIT, staffel(otherStaffelId), BankAccountStatus.ACTIVE);
    when(accountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(List.of(cartelAccount, staffelAccount, closed, foreign));
    when(viewGrantRepository.findByAccountIdIn(anyCollection())).thenReturn(List.of());
    when(approvalLimitRepository.findByAccountIdIn(anyCollection())).thenReturn(List.of());
    BankActor overseer = TestBankActor.member(UUID.randomUUID()).reach(staffelId).build();

    assertThat(policy.viewableAccounts(overseer, null))
        .extracting(BankAccountAccess.Visible::accountId)
        .containsExactly(cartelAccount.getId(), staffelAccount.getId());
  }

  @Test
  void admit_depositOnAHiddenAccount_needsNoApproval() {
    stubFind(staffelAccount);

    assertThat(
            policy.admit(
                TestBankActor.member(UUID.randomUUID()).build(),
                staffelAccount.getId(),
                null,
                BankBookingRequestType.DEPOSIT,
                new BigDecimal("50000")))
        .isEqualTo(BankAdmission.Admitted.UNROUTED);
    verify(viewGrantRepository, never()).findByAccountId(any());
  }

  @Test
  void admit_withdrawalFromAHiddenAccount_answersNotFound() {
    stubFind(staffelAccount);

    assertThat(
            policy.admit(
                TestBankActor.member(UUID.randomUUID()).build(),
                staffelAccount.getId(),
                null,
                BankBookingRequestType.WITHDRAWAL,
                BigDecimal.ONE))
        .isEqualTo(BankAdmission.Rejection.NOT_FOUND);
  }

  @Test
  void admit_withdrawalFromAViewableSonderkonto_isNotRequestCapable() {
    BankAccount special = account(BankAccountType.SPECIAL, null, BankAccountStatus.ACTIVE);
    stubFind(special);
    BankActor ol =
        TestBankActor.member(UUID.randomUUID())
            .seat(UUID.randomUUID(), MembershipRole.OL_MEMBER)
            .build();

    assertThat(
            policy.admit(
                ol, special.getId(), null, BankBookingRequestType.WITHDRAWAL, BigDecimal.ONE))
        .isEqualTo(BankAdmission.Rejection.NOT_REQUEST_CAPABLE);
  }

  @Test
  void admit_krtWithdrawal_followsTheAmountLadder() {
    stubFind(cartelAccount);
    BankActor member = TestBankActor.member(UUID.randomUUID()).build();

    assertThat(admitWithdrawal(member, cartelAccount, "500"))
        .isEqualTo(BankAdmission.Admitted.withoutApproval(T1));
    assertThat(admitWithdrawal(member, cartelAccount, "5000"))
        .isEqualTo(BankAdmission.Admitted.approvedBy(T1, BankRequestApprover.BANK_MANAGEMENT));
    assertThat(admitWithdrawal(member, cartelAccount, "50000"))
        .isEqualTo(BankAdmission.Admitted.approvedBy(T1, BankRequestApprover.ORGANISATIONSLEITUNG));
  }

  @Test
  void admit_krtWithdrawalByAnOlMember_isExemptFromTheLadder() {
    stubFind(cartelAccount);
    BankActor ol =
        TestBankActor.member(UUID.randomUUID())
            .seat(UUID.randomUUID(), MembershipRole.OL_MEMBER)
            .build();

    assertThat(admitWithdrawal(ol, cartelAccount, "50000"))
        .isEqualTo(BankAdmission.Admitted.UNROUTED);
  }

  @Test
  void admit_staffelWithdrawalOverTheMemberLimit_needsTheResponsibleHolder() {
    stubFind(staffelAccount);
    BankAccountApprovalLimit allMembers = new BankAccountApprovalLimit();
    allMembers.setAccount(staffelAccount);
    allMembers.setGranteeKind(BankAccountViewGranteeKind.ALL_MEMBERS);
    allMembers.setLimitAmount(T1);
    when(approvalLimitRepository.findByAccountId(staffelAccount.getId()))
        .thenReturn(List.of(allMembers));
    BankActor member =
        TestBankActor.member(UUID.randomUUID())
            .seat(staffelId, MembershipRole.MEMBER)
            .reach(staffelId)
            .build();

    assertThat(admitWithdrawal(member, staffelAccount, "500"))
        .isEqualTo(BankAdmission.Admitted.withoutApproval(T1));
    assertThat(admitWithdrawal(member, staffelAccount, "5000"))
        .isEqualTo(BankAdmission.Admitted.approvedBy(T1, BankRequestApprover.RESPONSIBLE_HOLDER));
  }

  @Test
  void staffRights_employeeConfirmsOnlyWhatTheGrantAllows() {
    UUID accountId = UUID.randomUUID();
    BankStaffRights rights =
        policy.staffRights(
            TestBankActor.of(UUID.randomUUID())
                .roles("BANK_EMPLOYEE")
                .grant(accountId, false, true, false)
                .build());

    assertThat(rights.canSee(accountId)).isTrue();
    assertThat(rights.canBook(BankBookingRequestType.WITHDRAWAL, accountId)).isTrue();
    assertThat(rights.canBook(BankBookingRequestType.DEPOSIT, accountId)).isFalse();
    assertThat(rights.canSee(UUID.randomUUID())).isFalse();
    assertThat(rights.grantedAccountIds()).containsExactly(accountId);
  }

  @Test
  void staffRights_fourEyesBindsTheRequesterButNotAnAdmin() {
    UUID requester = UUID.randomUUID();

    assertThat(
            policy
                .staffRights(TestBankActor.of(requester).bankManagement().build())
                .boundByFourEyes(requester))
        .isTrue();
    assertThat(
            policy
                .staffRights(TestBankActor.of(requester).admin().build())
                .boundByFourEyes(requester))
        .isFalse();
  }

  @Test
  void staffRights_bandApproversFollowTheirRoleAndSeat() {
    BankStaffRights management =
        policy.staffRights(TestBankActor.of(UUID.randomUUID()).bankManagement().build());
    BankStaffRights ol =
        policy.staffRights(
            TestBankActor.member(UUID.randomUUID())
                .seat(UUID.randomUUID(), MembershipRole.OL_MEMBER)
                .build());

    assertThat(management.mayApprove(BankRequestApprover.BANK_MANAGEMENT, false)).isTrue();
    assertThat(management.mayApprove(BankRequestApprover.ORGANISATIONSLEITUNG, false)).isFalse();
    assertThat(ol.mayApprove(BankRequestApprover.ORGANISATIONSLEITUNG, false)).isTrue();
    assertThat(ol.mayApprove(BankRequestApprover.RESPONSIBLE_HOLDER, false)).isFalse();
    assertThat(ol.mayApprove(BankRequestApprover.RESPONSIBLE_HOLDER, true)).isTrue();
  }

  @Test
  void staffRights_ofNobody_allowNothing() {
    BankStaffRights rights = policy.staffRights(BankActor.NOBODY);

    assertThat(rights.staff()).isFalse();
    assertThat(rights.canSee(UUID.randomUUID())).isFalse();
    assertThat(rights.maySeeHolder(null)).isFalse();
  }

  private BankAdmission admitWithdrawal(BankActor actor, BankAccount account, String amount) {
    return policy.admit(
        actor, account.getId(), null, BankBookingRequestType.WITHDRAWAL, new BigDecimal(amount));
  }

  private void stubFind(BankAccount account) {
    when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
  }

  private static Squadron staffel(UUID id) {
    Squadron squadron = new Squadron();
    squadron.setId(id);
    squadron.setName("Staffel " + id);
    return squadron;
  }

  private static BankAccount account(
      BankAccountType type, Squadron owner, BankAccountStatus status) {
    BankAccount account = new BankAccount();
    account.setId(UUID.randomUUID());
    account.setAccountNo("KB-" + UUID.randomUUID());
    account.setType(type);
    account.setStatus(status);
    account.setOrgUnit(owner);
    return account;
  }
}
