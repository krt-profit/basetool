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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountRight;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link OrgUnitBankAccessService} with {@link OrgUnitBankSettingsService} and
 * {@link OrgUnitBankRequestService} over a real {@link BankAccessRules} (REQ-BANK-021): visibility
 * per account type, the holder-redacted drill-in, the balance-target gate, view grants and booking
 * requests.
 *
 * <p>Lenient strictness allows shared per-test stubs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgUnitBankAccessServiceTest {

  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private BankCaller bankCaller;
  @Mock private BankAccountRepository bankAccountRepository;
  @Mock private BankPostingRepository bankPostingRepository;
  @Mock private BankAccountViewGrantRepository viewGrantRepository;
  @Mock private BankAccountApprovalLimitRepository approvalLimitRepository;
  @Mock private BankBookingRequestRepository bankBookingRequestRepository;
  @Mock private BereichRepository bereichRepository;
  @Mock private UserRepository userRepository;
  @Mock private BankAccountService bankAccountService;
  @Mock private BankApprovalLimitService bankApprovalLimitService;
  @Mock private BankStatementReportService bankStatementReportService;
  @Mock private BankBookingRequestService bankBookingRequestService;
  @Mock private BankAuditService bankAuditService;
  @Mock private OrgUnitRepository orgUnitRepository;

  private OrgUnitBankAccessService service;
  private OrgUnitBankSettingsService settingsService;
  private OrgUnitBankRequestService requestService;

  /** The actor {@link #actingAs(BankActor)} made the caller. */
  private BankActor callingActor;

  @BeforeEach
  void defaultStubs() {
    actingAs(BankActor.NOBODY);
    when(requestScopeResolver.readActiveSquadronFromHeader()).thenReturn(Optional.empty());
    when(viewGrantRepository.findByAccountIdIn(anyCollection())).thenReturn(List.of());
    when(viewGrantRepository.findByAccountId(any())).thenReturn(List.of());
    when(approvalLimitRepository.findByAccountIdIn(anyCollection())).thenReturn(List.of());
    when(approvalLimitRepository.findByAccountId(any())).thenReturn(List.of());
    when(bankPostingRepository.postingSlicesSince(anyCollection(), any())).thenReturn(List.of());
    wireDelegates();
  }

  /**
   * Builds the seam and its settings and request services on the scenario mocks, with a real access
   * policy and real visibility and approval-limit collaborators.
   */
  private void wireDelegates() {
    BankAccessRules policy =
        new BankAccessRules(
            bankAccountRepository,
            viewGrantRepository,
            approvalLimitRepository,
            bereichRepository,
            orgUnitRepository);
    OrgUnitBankVisibilityService visibilityService =
        new OrgUnitBankVisibilityService(viewGrantRepository, userRepository, bankAuditService);
    OrgUnitBankApprovalLimitService approvalLimitService =
        new OrgUnitBankApprovalLimitService(
            bankAccountRepository, approvalLimitRepository, userRepository, bankAuditService);
    service =
        new OrgUnitBankAccessService(
            requestScopeResolver,
            bankCaller,
            policy,
            bankAccountRepository,
            bankPostingRepository,
            bankAccountService,
            bankStatementReportService);
    settingsService =
        new OrgUnitBankSettingsService(
            service,
            bankAccountRepository,
            viewGrantRepository,
            userRepository,
            bankApprovalLimitService,
            bankAuditService,
            visibilityService,
            approvalLimitService);
    requestService =
        new OrgUnitBankRequestService(
            service,
            policy,
            bankAccountRepository,
            bankBookingRequestRepository,
            bankBookingRequestService,
            null);
  }

  /**
   * Makes the actor the caller of the request.
   *
   * @param actor the caller's actor
   */
  private void actingAs(BankActor actor) {
    callingActor = actor;
    when(bankCaller.actor()).thenReturn(actor);
  }

  /**
   * Makes the builder's actor the caller of the request.
   *
   * @param actor the caller's actor builder
   */
  private void actingAs(TestBankActor actor) {
    actingAs(actor.build());
  }

  /**
   * Sends the org unit as the request's active-org-unit pin.
   *
   * @param orgUnitId the pinned org unit
   */
  private void pinned(UUID orgUnitId) {
    when(requestScopeResolver.readActiveSquadronFromHeader()).thenReturn(Optional.of(orgUnitId));
  }

  private static OrgUnit squadron(UUID id, String name, String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setId(id);
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    return squadron;
  }

  private static OrgUnit bereich(UUID id, String name, String shorthand) {
    Bereich bereich = new Bereich();
    bereich.setId(id);
    bereich.setName(name);
    bereich.setShorthand(shorthand);
    return bereich;
  }

  private static BankAccount account(UUID id, String accountNo, OrgUnit orgUnit) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo(accountNo);
    account.setName(accountNo + " account");
    account.setType(orgUnit == null ? BankAccountType.AREA : BankAccountType.ORG_UNIT);
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setOrgUnit(orgUnit);
    return account;
  }

  private static BankAccount typedAccount(
      UUID id, String accountNo, BankAccountType type, OrgUnit orgUnit) {
    BankAccount account = account(id, accountNo, orgUnit);
    account.setType(type);
    return account;
  }

  private static BankAccount specialAccount(UUID id, String accountNo, BankAccountStatus status) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo(accountNo);
    account.setName(accountNo + " special account");
    account.setType(BankAccountType.SPECIAL);
    account.setStatus(status);
    account.setOrgUnit(null);
    return account;
  }

  @Test
  void listOverseenOrgUnitBalances_returnsOnlyAccountsTheOfficerOversees() {
    UUID ownStaffelId = UUID.randomUUID();
    UUID ownAccountId = UUID.randomUUID();
    OrgUnit ownStaffel = squadron(ownStaffelId, "Own Staffel", "OWN");
    BankAccount ownAccount = account(ownAccountId, "KB-0001", ownStaffel);
    BankAccount foreignAccount =
        account(UUID.randomUUID(), "KB-0002", squadron(UUID.randomUUID(), "Foreign", "FRG"));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(ownStaffelId));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(List.of(ownAccount, foreignAccount));
    when(bankPostingRepository.accountBalances(List.of(ownAccountId)))
        .thenReturn(List.of(new BankAccountBalance(ownAccountId, new BigDecimal("12345"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result).hasSize(1);
    OrgUnitBankBalanceDto dto = result.getFirst();
    assertThat(dto.accountId()).isEqualTo(ownAccountId);
    assertThat(dto.orgUnitId()).isEqualTo(ownStaffelId);
    assertThat(dto.balance()).isEqualByComparingTo("12345");
    assertThat(dto.canRequest()).isTrue();
  }

  @Test
  void listOverseenOrgUnitBalances_emptyForCallerWithoutOversight() {
    actingAs(TestBankActor.member(UUID.randomUUID()));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(
            List.of(account(UUID.randomUUID(), "KB-0001", squadron(UUID.randomUUID(), "S", "S"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result).isEmpty();
    verify(bankPostingRepository, never()).accountBalances(anyCollection());
  }

  @Test
  void listOverseenOrgUnitBalances_adminSeesEveryOrgUnitAccountAndZeroWhenNoPostings() {
    actingAs(TestBankActor.of(UUID.randomUUID()).admin());
    UUID accountAId = UUID.randomUUID();
    UUID accountBId = UUID.randomUUID();
    BankAccount accountA = account(accountAId, "KB-0001", squadron(UUID.randomUUID(), "A", "AAA"));
    BankAccount accountB = account(accountBId, "KB-0002", squadron(UUID.randomUUID(), "B", "BBB"));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(List.of(accountA, accountB));
    when(bankPostingRepository.accountBalances(List.of(accountAId, accountBId)))
        .thenReturn(List.of(new BankAccountBalance(accountAId, new BigDecimal("500"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result)
        .extracting(OrgUnitBankBalanceDto::accountId)
        .containsExactlyInAnyOrder(accountAId, accountBId);
    assertThat(result)
        .filteredOn(dto -> dto.accountId().equals(accountBId))
        .singleElement()
        .satisfies(dto -> assertThat(dto.balance()).isEqualByComparingTo("0"));
  }

  @Test
  void listOverseenOrgUnitBalances_holderGrantedMembershipRole_makesAccountVisible() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    OrgUnit staffel = squadron(staffelId, "Own", "OWN");
    BankAccount account = account(accountId, "KB-0001", staffel);
    BankAccountViewGrant grant = new BankAccountViewGrant();
    grant.setAccount(account);
    grant.setGranteeKind(BankAccountViewGranteeKind.MEMBERSHIP_ROLE);
    grant.setRoleCode(MembershipRole.ENSIGN.name());
    // Without the KRT Member role the seat's own-unit reach confers no oversight.
    actingAs(TestBankActor.member(UUID.randomUUID()).seat(staffelId, MembershipRole.ENSIGN));
    when(viewGrantRepository.findByAccountIdIn(anyCollection())).thenReturn(List.of(grant));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc()).thenReturn(List.of(account));
    when(bankPostingRepository.accountBalances(List.of(accountId)))
        .thenReturn(List.of(new BankAccountBalance(accountId, new BigDecimal("7"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result).extracting(OrgUnitBankBalanceDto::accountId).containsExactly(accountId);
    assertThat(result.getFirst().canRequest()).isTrue();
  }

  @Test
  void listOverseenOrgUnitBalances_cartelAccountVisibleToAnyMember() {
    UUID olId = UUID.randomUUID();
    UUID cartelId = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    ol.setName("OL");
    ol.setShorthand("OL");
    BankAccount cartel = typedAccount(cartelId, "KB-0001", BankAccountType.CARTEL, ol);
    actingAs(TestBankActor.member(UUID.randomUUID()));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc()).thenReturn(List.of(cartel));
    when(bankPostingRepository.accountBalances(List.of(cartelId)))
        .thenReturn(List.of(new BankAccountBalance(cartelId, new BigDecimal("42"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result).extracting(OrgUnitBankBalanceDto::accountId).containsExactly(cartelId);
  }

  @Test
  void listOverseenOrgUnitBalances_cartelBankVisibleOnlyToProfitBereichsleiter() {
    UUID profitBereichId = UUID.randomUUID();
    UUID cartelBankId = UUID.randomUUID();
    Bereich profit = new Bereich();
    profit.setId(profitBereichId);
    profit.setName("Profit");
    BankAccount cartelBank =
        typedAccount(cartelBankId, "KB-0001", BankAccountType.CARTEL_BANK, null);
    actingAs(
        TestBankActor.member(UUID.randomUUID())
            .seat(profitBereichId, MembershipRole.BEREICHSLEITER));
    when(bereichRepository.findByDepartment(any())).thenReturn(List.of(profit));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc()).thenReturn(List.of(cartelBank));
    when(bankPostingRepository.accountBalances(List.of(cartelBankId)))
        .thenReturn(List.of(new BankAccountBalance(cartelBankId, new BigDecimal("9"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result).extracting(OrgUnitBankBalanceDto::accountId).containsExactly(cartelBankId);
  }

  @Test
  void listOverseenOrgUnitBalances_specialAccountsSeenByBereichsleiterNotByOfficer() {
    UUID specialId = UUID.randomUUID();
    BankAccount special = specialAccount(specialId, "KB-0001", BankAccountStatus.ACTIVE);
    actingAs(
        TestBankActor.member(UUID.randomUUID())
            .seat(UUID.randomUUID(), MembershipRole.BEREICHSLEITER));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc()).thenReturn(List.of(special));
    when(bankPostingRepository.accountBalances(List.of(specialId)))
        .thenReturn(List.of(new BankAccountBalance(specialId, new BigDecimal("999"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result).extracting(OrgUnitBankBalanceDto::accountId).containsExactly(specialId);
    assertThat(result.getFirst().canRequest()).isFalse();
    assertThat(result.getFirst().orgUnitId()).isNull();
  }

  @Test
  void listOverseenOrgUnitBalances_specialAccountsHiddenFromOfficer() {
    UUID staffelId = UUID.randomUUID();
    UUID ownAccountId = UUID.randomUUID();
    BankAccount ownAccount = account(ownAccountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    BankAccount special = specialAccount(UUID.randomUUID(), "KB-0002", BankAccountStatus.ACTIVE);
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(staffelId));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(List.of(ownAccount, special));
    when(bankPostingRepository.accountBalances(List.of(ownAccountId)))
        .thenReturn(List.of(new BankAccountBalance(ownAccountId, new BigDecimal("10"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result).extracting(OrgUnitBankBalanceDto::accountId).containsExactly(ownAccountId);
  }

  @Test
  void listViewableActiveAccounts_unpinned_equalsTheOrgUnitBankPageSet() {
    UUID staffelId = UUID.randomUUID();
    UUID ownAccountId = UUID.randomUUID();
    UUID cartelId = UUID.randomUUID();
    BankAccount cartel =
        typedAccount(
            cartelId, "KB-0001", BankAccountType.CARTEL, squadron(UUID.randomUUID(), "OL", "OL"));
    BankAccount ownAccount = account(ownAccountId, "KB-0002", squadron(staffelId, "Own", "OWN"));
    BankAccount foreignAccount =
        account(UUID.randomUUID(), "KB-0003", squadron(UUID.randomUUID(), "Foreign", "FRG"));
    BankAccount closedOwn =
        account(UUID.randomUUID(), "KB-0004", squadron(staffelId, "Own", "OWN"));
    closedOwn.setStatus(BankAccountStatus.CLOSED);
    BankAccount special = specialAccount(UUID.randomUUID(), "KB-0005", BankAccountStatus.ACTIVE);
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(staffelId));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(List.of(cartel, ownAccount, foreignAccount, closedOwn, special));
    when(bankPostingRepository.accountBalances(anyCollection())).thenReturn(List.of());

    List<UUID> pageSet =
        service.listOverseenOrgUnitBalances().stream()
            .map(OrgUnitBankBalanceDto::accountId)
            .toList();
    List<UUID> visibleSet =
        service.listViewableActiveAccounts().stream().map(BankAccount::getId).toList();

    assertThat(visibleSet).containsExactly(cartelId, ownAccountId).isEqualTo(pageSet);
  }

  @Test
  void listViewableActiveAccounts_pinDoesNotNarrowTheSet() {
    UUID staffelAId = UUID.randomUUID();
    UUID staffelBId = UUID.randomUUID();
    UUID accountAId = UUID.randomUUID();
    UUID accountBId = UUID.randomUUID();
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(staffelAId, staffelBId));
    pinned(staffelAId);
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(
            List.of(
                account(accountAId, "KB-0001", squadron(staffelAId, "A", "AAA")),
                account(accountBId, "KB-0002", squadron(staffelBId, "B", "BBB"))));

    List<BankAccount> visible = service.listViewableActiveAccounts();

    assertThat(visible).extracting(BankAccount::getId).containsExactly(accountAId, accountBId);
  }

  @Test
  void listViewableActiveAccounts_plainMemberAlwaysSeesTheKrtAccount() {
    UUID cartelId = UUID.randomUUID();
    BankAccount cartel =
        typedAccount(
            cartelId, "KB-0001", BankAccountType.CARTEL, squadron(UUID.randomUUID(), "OL", "OL"));
    actingAs(TestBankActor.member(UUID.randomUUID()));
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(
            List.of(
                cartel,
                account(UUID.randomUUID(), "KB-0002", squadron(UUID.randomUUID(), "S", "S"))));

    List<BankAccount> visible = service.listViewableActiveAccounts();

    assertThat(visible).extracting(BankAccount::getId).containsExactly(cartelId);
  }

  @Test
  void activeContextOrgUnitIds_pinned_isThePinnedUnit() {
    UUID pinnedUnit = UUID.randomUUID();
    actingAs(
        TestBankActor.member(UUID.randomUUID())
            .seat(pinnedUnit, MembershipRole.MEMBER)
            .seat(UUID.randomUUID(), MembershipRole.MEMBER));
    pinned(pinnedUnit);

    assertThat(service.activeContextOrgUnitIds()).containsExactly(pinnedUnit);
  }

  @Test
  void activeContextOrgUnitIds_unpinned_isEveryDirectMembership() {
    UUID unitA = UUID.randomUUID();
    UUID unitB = UUID.randomUUID();
    actingAs(
        TestBankActor.member(UUID.randomUUID())
            .seat(unitA, MembershipRole.MEMBER)
            .seat(unitB, MembershipRole.MEMBER)
            .reach(unitA, unitB, UUID.randomUUID()));

    assertThat(service.activeContextOrgUnitIds()).containsExactlyInAnyOrder(unitA, unitB);
  }

  @Test
  void getViewableAccountBookings_redactsHolderHandles() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(staffelId));
    BankBookingDto raw =
        new BankBookingDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            BankTransactionType.TRANSFER,
            new BigDecimal("-100"),
            "greluc",
            "note",
            "Reparatur",
            "intern: Rueckfrage SL",
            Instant.now(),
            null,
            "KB-0002",
            "Other",
            "counterHolder",
            false,
            BigDecimal.ZERO,
            "carol",
            "Staffel Rot");
    when(bankAccountService.getBookings(eq(accountId), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(raw)));

    var page = service.getViewableAccountBookings(accountId, PageRequest.of(0, 20), null, null);

    BankBookingDto redacted = page.getContent().getFirst();
    assertThat(redacted.holderHandle()).isNull();
    assertThat(redacted.counterHolderHandle()).isNull();
    assertThat(redacted.counterpartyHandle()).isEqualTo("carol");
    assertThat(redacted.counterpartyOrgUnitName()).isEqualTo("Staffel Rot");
    assertThat(redacted.counterAccountNo()).isEqualTo("KB-0002");
    assertThat(redacted.note()).isEqualTo("note");
    assertThat(redacted.justification()).isEqualTo("Reparatur");
    assertThat(redacted.staffNote()).isNull();
    assertThat(redacted.amount()).isEqualByComparingTo("-100");
  }

  @Test
  void getViewableAccountBookings_notFoundWhenCallerMayNotView() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()));

    assertThrows(
        NotFoundException.class,
        () -> service.getViewableAccountBookings(accountId, PageRequest.of(0, 20), null, null));
    verifyNoInteractions(bankStatementReportService);
  }

  @Test
  void getViewableBalanceSeries_delegatesForViewableAccount() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(staffelId));
    Instant from = Instant.parse("2026-06-01T00:00:00Z");
    Instant to = Instant.parse("2026-07-01T00:00:00Z");
    BankBalanceSeriesDto expected =
        new BankBalanceSeriesDto(
            List.of(new BankBalancePointDto(from, new BigDecimal("100"))), new BigDecimal("500"));
    when(bankAccountService.getBalanceSeries(accountId, from, to)).thenReturn(expected);

    assertThat(service.getViewableBalanceSeries(accountId, from, to)).isSameAs(expected);
  }

  @Test
  void getViewableBalanceSeries_notFoundWhenCallerMayNotView() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()));

    assertThrows(
        NotFoundException.class,
        () -> service.getViewableBalanceSeries(accountId, Instant.now(), Instant.now()));
    verifyNoInteractions(bankStatementReportService);
  }

  @Test
  void getViewableAccountDetail_notFoundWhenCallerMayNotView() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()));

    assertThrows(NotFoundException.class, () -> service.getViewableAccountDetail(accountId));
    verify(bankAccountService, never()).getAccountDetail(any(), any());
  }

  @Test
  void exportViewableStatement_authorizesAndPassesRedactionFlag() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    Instant from = Instant.now().minusSeconds(3600);
    Instant to = Instant.now();
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(staffelId));
    when(bankStatementReportService.generateStatement(
            eq(accountId), eq(from), eq(to), any(), eq(true)))
        .thenReturn(new byte[] {1, 2, 3});

    byte[] pdf = service.exportViewableStatement(accountId, from, to, null);

    assertThat(pdf).hasSize(3);
    verify(bankStatementReportService)
        .generateStatement(eq(accountId), eq(from), eq(to), any(), eq(true));
  }

  @Test
  void setBalanceTarget_byResponsibleHolder_savesAndAudits() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    account.setVersion(3L);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).seat(staffelId, MembershipRole.STAFFELLEITER));
    when(bankAccountRepository.saveAndFlush(account)).thenReturn(account);

    var settings = settingsService.setBalanceTarget(accountId, new BigDecimal("10000"), 3L);

    assertThat(settings.balanceTarget()).isEqualByComparingTo("10000");
    assertThat(account.getBalanceTarget()).isEqualByComparingTo("10000");
    verify(bankAuditService)
        .record(eq(BankAuditEventType.BALANCE_TARGET_SET), eq(accountId), any(), any(), any());
  }

  @Test
  void setBalanceTarget_byNonHolder_throwsAccessDenied() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    account.setVersion(1L);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(staffelId));

    assertThrows(
        AccessDeniedException.class,
        () -> settingsService.setBalanceTarget(accountId, new BigDecimal("10000"), 1L));
    verify(bankAccountRepository, never()).saveAndFlush(any());
  }

  @Test
  void setBalanceTarget_versionMismatch_throwsOptimisticLock() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    account.setVersion(5L);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).seat(staffelId, MembershipRole.STAFFELLEITER));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> settingsService.setBalanceTarget(accountId, new BigDecimal("1"), 4L));
  }

  @Test
  void admin_maySetTargetAndConfigureVisibilityOnAnyAccountWithoutBeingHolder() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    account.setVersion(1L);
    actingAs(TestBankActor.of(UUID.randomUUID()).admin());
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(bankAccountRepository.saveAndFlush(account)).thenReturn(account);
    when(viewGrantRepository.existsByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, MembershipRole.ENSIGN.name()))
        .thenReturn(false);

    settingsService.setBalanceTarget(accountId, new BigDecimal("100"), 1L);
    settingsService.addRoleVisibility(accountId, MembershipRole.ENSIGN.name());

    verify(bankAuditService)
        .record(eq(BankAuditEventType.BALANCE_TARGET_SET), eq(accountId), any(), any(), any());
    verify(viewGrantRepository).save(any(BankAccountViewGrant.class));
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BALANCE_VISIBILITY_GRANTED), eq(accountId), any(), any(), any());
  }

  @Test
  void addRoleVisibility_byHolder_savesGrantAndAudits() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).seat(staffelId, MembershipRole.STAFFELLEITER));
    when(viewGrantRepository.existsByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, MembershipRole.ENSIGN.name()))
        .thenReturn(false);

    settingsService.addRoleVisibility(accountId, MembershipRole.ENSIGN.name());

    verify(viewGrantRepository).save(any(BankAccountViewGrant.class));
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BALANCE_VISIBILITY_GRANTED), eq(accountId), any(), any(), any());
  }

  @Test
  void addRoleVisibility_unknownBucket_throwsBadRequest() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).seat(staffelId, MembershipRole.STAFFELLEITER));

    assertThrows(
        BadRequestException.class,
        () ->
            settingsService.addRoleVisibility(
                accountId, MembershipRole.BEREICHSKOORDINATOR.name()));
    verify(viewGrantRepository, never()).save(any());
  }

  @Test
  void addRoleVisibility_globalRoleOnSonderkonto_throwsBadRequest() {
    UUID accountId = UUID.randomUUID();
    BankAccount special = specialAccount(accountId, "KB-0007", BankAccountStatus.ACTIVE);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(special));
    actingAs(TestBankActor.of(UUID.randomUUID()).admin());

    assertThrows(
        BadRequestException.class,
        () -> settingsService.addRoleVisibility(accountId, "LOGISTICIAN"));
    assertThrows(
        BadRequestException.class,
        () -> settingsService.addRoleVisibility(accountId, "MISSION_MANAGER"));
    verify(viewGrantRepository, never()).save(any());
  }

  @Test
  void getAccountSettings_sonderkonto_offersNoRoleBuckets() {
    UUID accountId = UUID.randomUUID();
    BankAccount special = specialAccount(accountId, "KB-0008", BankAccountStatus.ACTIVE);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(special));
    actingAs(TestBankActor.of(UUID.randomUUID()).admin());

    assertThat(settingsService.getAccountSettings(accountId).availableRoleCodes()).isEmpty();
  }

  @Test
  void accessTo_sonderkontoWithAllMembersGrant_admitsAMember() {
    BankAccount special = specialAccount(UUID.randomUUID(), "KB-0010", BankAccountStatus.ACTIVE);
    BankAccountViewGrant grant = new BankAccountViewGrant();
    grant.setGranteeKind(BankAccountViewGranteeKind.ALL_MEMBERS);
    when(viewGrantRepository.findByAccountId(special.getId())).thenReturn(List.of(grant));
    actingAs(TestBankActor.member(UUID.randomUUID()));

    assertThat(service.accessTo(special).has(BankAccountRight.VIEW)).isTrue();
  }

  @Test
  void createBookingRequest_viewableAccount_resolvesAndDelegates() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.DEPOSIT, null, new BigDecimal("500"), "from sale");
    BankBookingRequestDto expected = requestDto(accountId, orgUnitId);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.DEPOSIT,
                    new BigDecimal("500"),
                    "from sale",
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(expected);

    assertThat(requestService.createBookingRequest(request)).isSameAs(expected);
    verifyNoInteractions(requestScopeResolver);
  }

  @Test
  void createBookingRequest_splitDeposit_passesSplitSnapshotToCreate() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId,
            BankBookingRequestType.DEPOSIT,
            null,
            new BigDecimal("1000"),
            "from sale",
            null,
            true,
            new BigDecimal("30"),
            null,
            null);
    BankBookingRequestDto expected = requestDto(accountId, orgUnitId);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.DEPOSIT,
                    new BigDecimal("1000"),
                    "from sale",
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    true,
                    new BigDecimal("30"),
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(expected);

    assertThat(requestService.createBookingRequest(request)).isSameAs(expected);
    verifyNoInteractions(requestScopeResolver);
  }

  @Test
  void createBookingRequest_depositOnUnviewableAccount_succeedsWithoutApprovalOrLimit() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Foreign", "FRG"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.DEPOSIT, null, new BigDecimal("500"), "from sale");
    BankBookingRequestDto expected = requestDto(accountId, orgUnitId);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.DEPOSIT,
                    new BigDecimal("500"),
                    "from sale",
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(expected);

    assertThat(requestService.createBookingRequest(request)).isSameAs(expected);
    verifyNoInteractions(requestScopeResolver);
    verify(approvalLimitRepository, never()).findByAccountId(any());
  }

  @Test
  void createBookingRequest_depositOnSpecialAccount_succeeds() {
    UUID accountId = UUID.randomUUID();
    BankAccount special = specialAccount(accountId, "KB-0009", BankAccountStatus.ACTIVE);
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.DEPOSIT, null, new BigDecimal("250"), null);
    BankBookingRequestDto expected = requestDto(accountId, null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(special));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.DEPOSIT,
                    new BigDecimal("250"),
                    null,
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(expected);

    assertThat(requestService.createBookingRequest(request)).isSameAs(expected);
  }

  @Test
  void createBookingRequest_depositAboveConfiguredLimit_neverFlagsApproval() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID caller = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    BankAccountApprovalLimit limit = new BankAccountApprovalLimit();
    limit.setGranteeKind(BankAccountViewGranteeKind.USER);
    limit.setGranteeUserId(caller);
    limit.setLimitAmount(new BigDecimal("100"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.DEPOSIT, null, new BigDecimal("5000"), null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.DEPOSIT,
                    new BigDecimal("5000"),
                    null,
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, orgUnitId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.DEPOSIT,
                    new BigDecimal("5000"),
                    null,
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
    verify(approvalLimitRepository, never()).findByAccountId(any());
  }

  @Test
  void createBookingRequest_depositWithDestination_throwsBadRequest() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId,
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            new BigDecimal("10"),
            null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));

    assertThrows(BadRequestException.class, () -> requestService.createBookingRequest(request));
    verifyNoInteractions(bankBookingRequestService);
  }

  @Test
  void createBookingRequest_notViewable_throwsAccessDenied() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("500"), null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()));

    assertThrows(AccessDeniedException.class, () -> requestService.createBookingRequest(request));
    verifyNoInteractions(bankBookingRequestService);
  }

  @Test
  void createBookingRequest_aboveUserLimit_flagsRequiresOwnerApproval() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID caller = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("500"), null);
    BankAccountApprovalLimit limit = new BankAccountApprovalLimit();
    limit.setGranteeKind(BankAccountViewGranteeKind.USER);
    limit.setGranteeUserId(caller);
    limit.setLimitAmount(new BigDecimal("100"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(caller).reach(orgUnitId));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(limit));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("100"), BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, orgUnitId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("100"), BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_withdrawalNoLimit_alwaysFlagsRequiresOwnerApproval() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("500"), null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(orgUnitId));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of());
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(true, null, BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, orgUnitId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(true, null, BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_transfer_passesDestination() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID destId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.TRANSFER, destId, new BigDecimal("500"), null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(orgUnitId));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.TRANSFER,
                    new BigDecimal("500"),
                    null,
                    null,
                    destId,
                    new BankAdmission.Admitted(true, null, BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, orgUnitId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.TRANSFER,
                    new BigDecimal("500"),
                    null,
                    null,
                    destId,
                    new BankAdmission.Admitted(true, null, BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_nonMemberViewGrantHolder_notCappedByAllMembers_needsApproval() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID outsider = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("500"), null);
    BankAccountViewGrant viewGrant = new BankAccountViewGrant();
    viewGrant.setGranteeKind(BankAccountViewGranteeKind.USER);
    viewGrant.setGranteeUserId(outsider);
    BankAccountApprovalLimit allMembers = new BankAccountApprovalLimit();
    allMembers.setGranteeKind(BankAccountViewGranteeKind.ALL_MEMBERS);
    allMembers.setLimitAmount(new BigDecimal("100"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(outsider));
    when(viewGrantRepository.findByAccountId(accountId)).thenReturn(List.of(viewGrant));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(allMembers));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(true, null, BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, orgUnitId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(true, null, BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_allMembersLimit_appliesToActualOwningUnitMember() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("500"), null);
    BankAccountApprovalLimit allMembers = new BankAccountApprovalLimit();
    allMembers.setGranteeKind(BankAccountViewGranteeKind.ALL_MEMBERS);
    allMembers.setLimitAmount(new BigDecimal("100"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(member).reach(orgUnitId).seat(orgUnitId, MembershipRole.MEMBER));
    when(viewGrantRepository.findByAccountId(accountId)).thenReturn(List.of());
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(allMembers));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("100"), BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, orgUnitId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("100"), BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  /**
   * Builds an active KRT account with both approval thresholds set and stubs the request-create
   * preconditions: the caller is a plain member, who views the KRT account.
   *
   * @param accountId the KRT account id
   * @param t1 the bank-employee ceiling
   * @param t2 the area-lead ceiling
   * @return the stubbed KRT account
   */
  private BankAccount krtAccountWithTiers(UUID accountId, String t1, String t2) {
    BankAccount cartel =
        typedAccount(
            accountId, "KB-0003", BankAccountType.CARTEL, squadron(UUID.randomUUID(), "OL", "OL"));
    cartel.setEmployeeApprovalCeiling(new BigDecimal(t1));
    cartel.setAreaLeadApprovalCeiling(new BigDecimal(t2));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(cartel));
    when(viewGrantRepository.findByAccountId(accountId)).thenReturn(List.of());
    actingAs(TestBankActor.member(UUID.randomUUID()));
    return cartel;
  }

  @Test
  void createBookingRequest_krtWithinEmployeeCeiling_needsNoApproval() {
    UUID accountId = UUID.randomUUID();
    krtAccountWithTiers(accountId, "1000", "5000");
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("500"), "reason");
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    "reason",
                    null,
                    null,
                    new BankAdmission.Admitted(false, new BigDecimal("1000"), null),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    "reason",
                    null,
                    null,
                    new BankAdmission.Admitted(false, new BigDecimal("1000"), null),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_krtMiddleBand_routesToBereichsleiterProfit() {
    UUID accountId = UUID.randomUUID();
    krtAccountWithTiers(accountId, "1000", "5000");
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("3000"), "reason");
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("3000"),
                    "reason",
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.BANK_MANAGEMENT),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("3000"),
                    "reason",
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.BANK_MANAGEMENT),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_krtTopBand_routesToOrganisationsleitung() {
    UUID accountId = UUID.randomUUID();
    krtAccountWithTiers(accountId, "1000", "5000");
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("9000"), "reason");
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("9000"),
                    "reason",
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.ORGANISATIONSLEITUNG),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("9000"),
                    "reason",
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.ORGANISATIONSLEITUNG),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_byResponsibleHolder_needsNoApproval() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0020", squadron(orgUnitId, "Nemesis", "NEM"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("2000000"), null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(
        TestBankActor.member(UUID.randomUUID())
            .reach(orgUnitId)
            .seat(orgUnitId, MembershipRole.STAFFELLEITER));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("2000000"),
                    null,
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, orgUnitId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("2000000"),
                    null,
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
    verify(approvalLimitRepository, never()).findByAccountId(accountId);
  }

  @Test
  void createBookingRequest_byResponsibleHolderAboveConfiguredLimit_stillNeedsNoApproval() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0020", squadron(orgUnitId, "Nemesis", "NEM"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("2000000"), null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(
        TestBankActor.member(UUID.randomUUID())
            .reach(orgUnitId)
            .seat(orgUnitId, MembershipRole.STAFFELLEITER));
    when(bankBookingRequestService.create(any())).thenReturn(requestDto(accountId, orgUnitId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("2000000"),
                    null,
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_krtByOlMember_bypassesAmountLadder() {
    UUID accountId = UUID.randomUUID();
    krtAccountWithTiers(accountId, "1000", "5000");
    actingAs(
        TestBankActor.member(UUID.randomUUID()).seat(UUID.randomUUID(), MembershipRole.OL_MEMBER));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("9000"), "reason");
    when(bankBookingRequestService.create(any()))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("9000"),
                    "reason",
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void grantOwnerApproval_byNonResponsibleHolder_throwsAccessDenied() {
    UUID requestId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    BankBookingRequest bookingRequest = new BankBookingRequest();
    bookingRequest.setAccount(account);
    bookingRequest.setStatus(BankBookingRequestStatus.PENDING);
    bookingRequest.setRequiresOwnerApproval(true);
    when(bankBookingRequestRepository.findByIdForUpdate(requestId))
        .thenReturn(Optional.of(bookingRequest));
    actingAs(TestBankActor.member(UUID.randomUUID()));

    assertThrows(AccessDeniedException.class, () -> requestService.grantOwnerApproval(requestId));
    verifyNoInteractions(bankBookingRequestService);
  }

  @Test
  void grantOwnerApproval_byResponsibleHolder_delegates() {
    UUID requestId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    BankBookingRequest bookingRequest = new BankBookingRequest();
    bookingRequest.setAccount(account);
    bookingRequest.setStatus(BankBookingRequestStatus.PENDING);
    bookingRequest.setRequiresOwnerApproval(true);
    when(bankBookingRequestRepository.findByIdForUpdate(requestId))
        .thenReturn(Optional.of(bookingRequest));
    actingAs(TestBankActor.member(UUID.randomUUID()).seat(orgUnitId, MembershipRole.STAFFELLEITER));
    when(bankBookingRequestService.applyOwnerApprovalWithinTransaction(bookingRequest, true))
        .thenReturn(requestDto(accountId, orgUnitId));

    BankBookingRequestDto dto = requestService.grantOwnerApproval(requestId);

    assertThat(dto).isNotNull();
    verify(bankBookingRequestService).applyOwnerApprovalWithinTransaction(bookingRequest, true);
  }

  @Test
  void grantOwnerApproval_byAdmin_approvesAnyKrtBand() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount cartel =
        typedAccount(
            accountId, "KB-0003", BankAccountType.CARTEL, squadron(UUID.randomUUID(), "OL", "OL"));
    BankBookingRequest bookingRequest = new BankBookingRequest();
    bookingRequest.setAccount(cartel);
    bookingRequest.setStatus(BankBookingRequestStatus.PENDING);
    bookingRequest.setRequiresOwnerApproval(true);
    bookingRequest.setRequiredApprover(BankRequestApprover.BANK_MANAGEMENT);
    actingAs(TestBankActor.of(UUID.randomUUID()).admin());
    when(bankBookingRequestRepository.findByIdForUpdate(requestId))
        .thenReturn(Optional.of(bookingRequest));
    when(bankBookingRequestService.applyOwnerApprovalWithinTransaction(bookingRequest, true))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    BankBookingRequestDto dto = requestService.grantOwnerApproval(requestId);

    assertThat(dto).isNotNull();
    verify(bankBookingRequestService).applyOwnerApprovalWithinTransaction(bookingRequest, true);
  }

  @Test
  void createBookingRequest_krtAboveT1_unsetAreaLeadCeiling_routesToBankManagement() {
    UUID accountId = UUID.randomUUID();
    BankAccount cartel =
        typedAccount(
            accountId, "KB-0003", BankAccountType.CARTEL, squadron(UUID.randomUUID(), "OL", "OL"));
    cartel.setEmployeeApprovalCeiling(new BigDecimal("1000"));
    cartel.setAreaLeadApprovalCeiling(null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(cartel));
    when(viewGrantRepository.findByAccountId(accountId)).thenReturn(List.of());
    actingAs(TestBankActor.member(UUID.randomUUID()));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("9000"), "reason");
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("9000"),
                    "reason",
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.BANK_MANAGEMENT),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("9000"),
                    "reason",
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.BANK_MANAGEMENT),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void raiseCartelDirectBookingRequest_middleBand_routesToBankManagement() {
    UUID accountId = UUID.randomUUID();
    BankAccount cartel =
        typedAccount(
            accountId, "KB-0003", BankAccountType.CARTEL, squadron(UUID.randomUUID(), "OL", "OL"));
    cartel.setEmployeeApprovalCeiling(new BigDecimal("1000"));
    cartel.setAreaLeadApprovalCeiling(new BigDecimal("5000"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(cartel));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("3000"),
                    "note",
                    "reason",
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.BANK_MANAGEMENT),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    requestService.raiseCartelDirectBookingRequest(
        accountId,
        BankBookingRequestType.WITHDRAWAL,
        new BigDecimal("3000"),
        "note",
        "reason",
        null);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("3000"),
                    "note",
                    "reason",
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.BANK_MANAGEMENT),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void raiseCartelDirectBookingRequest_topBand_routesToOrganisationsleitung() {
    UUID accountId = UUID.randomUUID();
    BankAccount cartel =
        typedAccount(
            accountId, "KB-0003", BankAccountType.CARTEL, squadron(UUID.randomUUID(), "OL", "OL"));
    cartel.setEmployeeApprovalCeiling(new BigDecimal("1000"));
    cartel.setAreaLeadApprovalCeiling(new BigDecimal("5000"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(cartel));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("9000"),
                    null,
                    "reason",
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.ORGANISATIONSLEITUNG),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    requestService.raiseCartelDirectBookingRequest(
        accountId, BankBookingRequestType.WITHDRAWAL, new BigDecimal("9000"), null, "reason", null);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("9000"),
                    null,
                    "reason",
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("1000"), BankRequestApprover.ORGANISATIONSLEITUNG),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_userLimitWinsOverHigherRoleTier() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID caller = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("500"), null);
    BankAccountApprovalLimit roleLimit = new BankAccountApprovalLimit();
    roleLimit.setGranteeKind(BankAccountViewGranteeKind.MEMBERSHIP_ROLE);
    roleLimit.setRoleCode(MembershipRole.ENSIGN.name());
    roleLimit.setLimitAmount(new BigDecimal("1000"));
    BankAccountApprovalLimit userLimit = new BankAccountApprovalLimit();
    userLimit.setGranteeKind(BankAccountViewGranteeKind.USER);
    userLimit.setGranteeUserId(caller);
    userLimit.setLimitAmount(new BigDecimal("100"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(caller).reach(orgUnitId).seat(orgUnitId, MembershipRole.ENSIGN));
    when(approvalLimitRepository.findByAccountId(accountId))
        .thenReturn(List.of(roleLimit, userLimit));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("100"), BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, orgUnitId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(
                        true, new BigDecimal("100"), BankRequestApprover.RESPONSIBLE_HOLDER),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  @Test
  void createBookingRequest_multipleMatchedTiers_usesMostPermissiveMax() {
    UUID bereichId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID caller = UUID.randomUUID();
    BankAccount account =
        typedAccount(
            accountId, "KB-0005", BankAccountType.AREA, bereich(bereichId, "Profit", "PRF"));
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("500"), null);
    BankAccountApprovalLimit roleLimit = new BankAccountApprovalLimit();
    roleLimit.setGranteeKind(BankAccountViewGranteeKind.MEMBERSHIP_ROLE);
    roleLimit.setRoleCode(MembershipRole.BEREICHSKOORDINATOR.name());
    roleLimit.setLimitAmount(new BigDecimal("100"));
    BankAccountApprovalLimit areaMembers = new BankAccountApprovalLimit();
    areaMembers.setGranteeKind(BankAccountViewGranteeKind.AREA_MEMBERS);
    areaMembers.setLimitAmount(new BigDecimal("1000"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(
        TestBankActor.member(caller)
            .reach(bereichId)
            .seat(bereichId, MembershipRole.BEREICHSKOORDINATOR));
    when(approvalLimitRepository.findByAccountId(accountId))
        .thenReturn(List.of(roleLimit, areaMembers));
    when(bankBookingRequestService.create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(false, new BigDecimal("1000"), null),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null))))
        .thenReturn(requestDto(accountId, bereichId));

    requestService.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(
                new BankBookingRequestDraft(
                    callingActor,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    new BankAdmission.Admitted(false, new BigDecimal("1000"), null),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null)));
  }

  /**
   * REQ-BANK-054: the requester's own list is the member audience, so the seam blanks the bank
   * employee's "Notiz Bankmitarbeiter" before it can reach a template; the same DTO type carries
   * the note unredacted for the approval tab.
   */
  @Test
  void listOwnBookingRequests_redactsTheStaffNote() {
    UUID accountId = UUID.randomUUID();
    BankBookingRequestDto withNote =
        requestDto(accountId, UUID.randomUUID(), "Bar uebergeben, Zeuge greluc");
    when(bankBookingRequestService.pageForCurrentRequester(
            eq(EnumSet.allOf(BankBookingRequestStatus.class)), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(withNote)));

    List<BankBookingRequestDto> own = requestService.listOwnBookingRequests();

    assertThat(own).hasSize(1);
    assertThat(own.getFirst().staffNote()).isNull();
    assertThat(own.getFirst().note()).isEqualTo("from sale");
    assertThat(own.getFirst().id()).isEqualTo(withNote.id());
    assertThat(own.getFirst().version()).isEqualTo(withNote.version());
  }

  /**
   * REQ-BANK-022: the dashboard's page of the caller's own requests blanks the staff note too and
   * keeps the total over all pages; the states are passed through.
   */
  @Test
  void pageOwnBookingRequests_redactsTheStaffNoteAndKeepsTheTotal() {
    BankBookingRequestDto withNote =
        requestDto(UUID.randomUUID(), UUID.randomUUID(), "Bar uebergeben, Zeuge greluc");
    Set<BankBookingRequestStatus> decided =
        Set.of(BankBookingRequestStatus.CONFIRMED, BankBookingRequestStatus.REJECTED);
    Pageable pageable = PageRequest.of(0, 3);
    when(bankBookingRequestService.pageForCurrentRequester(decided, pageable))
        .thenReturn(new PageImpl<>(List.of(withNote), pageable, 9));

    Page<BankBookingRequestDto> page = requestService.pageOwnBookingRequests(decided, pageable);

    assertThat(page.getTotalElements()).isEqualTo(9);
    assertThat(page.getContent())
        .singleElement()
        .satisfies(row -> {
          assertThat(row.staffNote()).isNull();
          assertThat(row.id()).isEqualTo(withNote.id());
        });
  }

  /** No state filter means every state. */
  @Test
  void pageOwnBookingRequests_withoutStatesReadsEveryState() {
    Pageable pageable = PageRequest.of(0, 3);
    when(bankBookingRequestService.pageForCurrentRequester(
            EnumSet.allOf(BankBookingRequestStatus.class), pageable))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    assertThat(requestService.pageOwnBookingRequests(Set.of(), pageable).getContent()).isEmpty();
  }

  /**
   * The approver lens is not redacted: a responsible holder decides on the request and the
   * employee's note is part of that context (REQ-BANK-054). This is the negative control for {@link
   * #listOwnBookingRequests_redactsTheStaffNote()}: a blanket redaction in the shared {@code toDto}
   * would pass that test while emptying the approval tab.
   */
  @Test
  void listRequestsForResponsibleAccounts_keepsTheStaffNote() {
    UUID accountId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    actingAs(TestBankActor.of(UUID.randomUUID()).admin());
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(List.of(account(accountId, "KB-0001", null)));
    when(bankBookingRequestService.listForeign(any()))
        .thenReturn(List.of(requestDto(accountId, orgUnitId, "Bar uebergeben, Zeuge greluc")));

    List<BankBookingRequestDto> foreign = requestService.listRequestsForResponsibleAccounts();

    assertThat(foreign)
        .singleElement()
        .extracting(BankBookingRequestDto::staffNote)
        .isEqualTo("Bar uebergeben, Zeuge greluc");
    ArgumentCaptor<BankAccessRules.ForeignRequestScope> scope =
        ArgumentCaptor.forClass(BankAccessRules.ForeignRequestScope.class);
    verify(bankBookingRequestService).listForeign(scope.capture());
    assertThat(scope.getValue().candidateAccountIds()).containsExactly(accountId);
  }

  /**
   * Raising a request's amount past the requester's ceiling in an edit sets {@code
   * requiresOwnerApproval} (REQ-BANK-056).
   */
  @Test
  void updateOwnBookingRequest_raisingPastTheLimit_reArmsTheApprovalGate() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    BankBookingRequest existing = new BankBookingRequest();
    existing.setId(requestId);
    existing.setAccount(account);
    existing.setType(BankBookingRequestType.WITHDRAWAL);
    existing.setRequestedBy(requester);
    BankAccountApprovalLimit allMembers = new BankAccountApprovalLimit();
    allMembers.setGranteeKind(BankAccountViewGranteeKind.ALL_MEMBERS);
    allMembers.setLimitAmount(new BigDecimal("1000"));
    when(bankBookingRequestRepository.findById(requestId)).thenReturn(Optional.of(existing));
    actingAs(TestBankActor.member(requester).seat(orgUnitId, MembershipRole.MEMBER));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(allMembers));

    UpdateBankBookingRequest update =
        new UpdateBankBookingRequest(
            new BigDecimal("100000"), null, "reason", null, null, null, 0L);
    requestService.updateOwnBookingRequest(requestId, update);

    verify(bankBookingRequestService)
        .updateOwn(
            any(),
            eq(requestId),
            eq(update),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER));
  }

  /**
   * The mirror case: an edit that stays under the ceiling leaves the request approval-free, so a
   * correction of a typo does not drag a responsible holder into the loop.
   */
  @Test
  void updateOwnBookingRequest_stayingUnderTheLimit_needsNoApproval() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    BankBookingRequest existing = new BankBookingRequest();
    existing.setId(requestId);
    existing.setAccount(account);
    existing.setType(BankBookingRequestType.WITHDRAWAL);
    existing.setRequestedBy(requester);
    BankAccountApprovalLimit allMembers = new BankAccountApprovalLimit();
    allMembers.setGranteeKind(BankAccountViewGranteeKind.ALL_MEMBERS);
    allMembers.setLimitAmount(new BigDecimal("1000"));
    when(bankBookingRequestRepository.findById(requestId)).thenReturn(Optional.of(existing));
    actingAs(TestBankActor.member(requester).seat(orgUnitId, MembershipRole.MEMBER));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(allMembers));

    UpdateBankBookingRequest update =
        new UpdateBankBookingRequest(new BigDecimal("900"), null, "reason", null, null, null, 0L);
    requestService.updateOwnBookingRequest(requestId, update);

    verify(bankBookingRequestService)
        .updateOwn(
            any(), eq(requestId), eq(update), eq(false), eq(new BigDecimal("1000")), isNull());
  }

  /**
   * REQ-BANK-056: a request belonging to somebody else is reported as not found before any approval
   * limit is resolved, so the endpoint never reveals that the id exists.
   */
  @Test
  void updateOwnBookingRequest_foreignRequest_throwsNotFoundWithoutResolving() {
    UUID requestId = UUID.randomUUID();
    BankBookingRequest existing = new BankBookingRequest();
    existing.setId(requestId);
    existing.setAccount(
        account(UUID.randomUUID(), "KB-0001", squadron(UUID.randomUUID(), "O", "O")));
    existing.setType(BankBookingRequestType.WITHDRAWAL);
    existing.setRequestedBy(UUID.randomUUID());
    when(bankBookingRequestRepository.findById(requestId)).thenReturn(Optional.of(existing));
    actingAs(TestBankActor.of(UUID.randomUUID()));

    assertThrows(
        NotFoundException.class,
        () ->
            requestService.updateOwnBookingRequest(
                requestId,
                new UpdateBankBookingRequest(
                    new BigDecimal("900"), null, "reason", null, null, null, 0L)));
    verify(bankBookingRequestService, never())
        .updateOwn(any(), any(), any(), anyBoolean(), any(), any());
    verify(approvalLimitRepository, never()).findByAccountId(any());
  }

  private static BankBookingRequestDto requestDto(UUID accountId, UUID orgUnitId) {
    return requestDto(accountId, orgUnitId, null);
  }

  private static BankBookingRequestDto requestDto(
      UUID accountId, UUID orgUnitId, String staffNote) {
    return new BankBookingRequestDto(
        UUID.randomUUID(),
        accountId,
        "KB-0001",
        "Own Account",
        orgUnitId,
        "Own",
        "OWN",
        BankBookingRequestType.DEPOSIT,
        new BigDecimal("500"),
        "from sale",
        null,
        staffNote,
        BankBookingRequestStatus.PENDING,
        "requester",
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.now(),
        null,
        null,
        false,
        null,
        null,
        false,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0L,
        null);
  }

  @Test
  void getViewableAccountDetail_byOverseer_isReadOnlyWithTheCallersControls() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(staffelId));

    OrgUnitBankAccountDetailDto detail = service.getViewableAccountDetail(accountId);

    assertThat(detail.canRequest()).isTrue();
    assertThat(detail.canSetTarget()).isFalse();
    assertThat(detail.canConfigureVisibility()).isFalse();
    assertThat(detail.approvalExempt()).isFalse();
    verify(bankAccountService)
        .getAccountDetail(accountId, new BankCapabilitiesDto(false, false, false, false));
  }

  @Test
  void getViewableAccountDetail_outOfScope_throwsNotFound() {
    UUID accountId = UUID.randomUUID();
    BankAccount account =
        account(accountId, "KB-0002", squadron(UUID.randomUUID(), "Foreign", "FRG"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()));

    assertThrows(NotFoundException.class, () -> service.getViewableAccountDetail(accountId));
    verifyNoInteractions(bankAccountService);
  }

  @Test
  void getAccountSettings_byHolder_returnsTheSnapshot() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).seat(staffelId, MembershipRole.STAFFELLEITER));

    OrgUnitBankAccountSettingsDto settings = settingsService.getAccountSettings(accountId);

    assertThat(settings.accountId()).isEqualTo(accountId);
    assertThat(settings.canSetTarget()).isTrue();
    assertThat(settings.canConfigureVisibility()).isTrue();
  }

  @Test
  void getAccountSettings_byPlainViewer_throwsAccessDenied() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(account.getOrgUnit().getId()));

    assertThrows(AccessDeniedException.class, () -> settingsService.getAccountSettings(accountId));
  }

  /**
   * REQ-BANK-066: every org-unit bank route that takes an account id answers a hidden account with
   * the same 404 and problem detail as an unknown id, and touches nothing behind the gate.
   */
  @Test
  void accountRoutes_answerHiddenAndUnknownAccountsWithTheSameNotFound() {
    UUID unknownId = UUID.randomUUID();
    UUID hiddenId = UUID.randomUUID();
    when(bankAccountRepository.findById(unknownId)).thenReturn(Optional.empty());
    when(bankAccountRepository.findById(hiddenId))
        .thenReturn(
            Optional.of(
                account(hiddenId, "KB-0002", squadron(UUID.randomUUID(), "Foreign", "FRG"))));
    actingAs(TestBankActor.member(UUID.randomUUID()));

    accountRoutes()
        .forEach((route, call) -> {
          NotFoundException unknown =
              assertThrows(NotFoundException.class, () -> call.accept(unknownId), route);
          NotFoundException hidden =
              assertThrows(NotFoundException.class, () -> call.accept(hiddenId), route);
          assertThat(hidden.getMessage()).as(route).isEqualTo(unknown.getMessage());
        });
    verifyNoInteractions(bankAccountService, bankStatementReportService, bankAuditService);
  }

  /**
   * REQ-BANK-066: the responsible holder reaches the settings of their account even when the
   * active-org-unit pin hides it from their view scope.
   */
  @Test
  void getAccountSettings_byHolderOutsideTheViewScope_returnsTheSnapshot() {
    UUID staffelId = UUID.randomUUID();
    UUID otherUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(
        TestBankActor.member(UUID.randomUUID())
            .seat(staffelId, MembershipRole.STAFFELLEITER)
            .reach(otherUnitId));
    pinned(otherUnitId);

    assertThat(service.accessTo(account).has(BankAccountRight.VIEW)).isFalse();
    assertThat(settingsService.getAccountSettings(accountId).accountId()).isEqualTo(accountId);
  }

  /**
   * One call per org-unit bank route that takes an account id, keyed by its path segment.
   *
   * @return the route calls, each taking the account id under test
   */
  private Map<String, Consumer<UUID>> accountRoutes() {
    Instant from = Instant.parse("2026-06-01T00:00:00Z");
    Instant to = Instant.parse("2026-07-01T00:00:00Z");
    return Map.of(
        "accounts/{id}", service::getViewableAccountDetail,
        "transactions",
            id -> service.getViewableAccountBookings(id, PageRequest.of(0, 20), null, null),
        "balance-series", id -> service.getViewableBalanceSeries(id, from, to),
        "statement", id -> service.exportViewableStatement(id, from, to, null),
        "settings", settingsService::getAccountSettings,
        "balance-target", id -> settingsService.setBalanceTarget(id, BigDecimal.TEN, 0L),
        "visibility", id -> settingsService.setAllMembersVisibility(id, true),
        "approval-limit", id -> settingsService.setAllMembersApprovalLimit(id, BigDecimal.TEN));
  }

  @Test
  void setRoleApprovalLimit_byPlainViewer_throwsAccessDenied() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.member(UUID.randomUUID()).reach(account.getOrgUnit().getId()));

    assertThrows(
        AccessDeniedException.class,
        () ->
            settingsService.setRoleApprovalLimit(
                accountId, MembershipRole.ENSIGN.name(), new BigDecimal("1000")));
    verifyNoInteractions(bankAuditService);
  }

  @Test
  void setAllMembersVisibility_onCartelAccountByAdmin_throwsAccessDenied() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = typedAccount(accountId, "KB-0099", BankAccountType.CARTEL, null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    actingAs(TestBankActor.of(UUID.randomUUID()).admin());

    assertThrows(
        AccessDeniedException.class,
        () -> settingsService.setAllMembersVisibility(accountId, true));
    verifyNoInteractions(bankAuditService);
  }

  @Test
  void listTransferTargetAccounts_listsOnlyActiveAccounts() {
    BankAccount active = account(UUID.randomUUID(), "KB-0001", null);
    BankAccount closed = account(UUID.randomUUID(), "KB-0002", null);
    closed.setStatus(BankAccountStatus.CLOSED);
    when(bankAccountRepository.findAllByOrderByAccountNoAsc()).thenReturn(List.of(active, closed));

    List<BankAccountRefDto> targets = requestService.listTransferTargetAccounts();

    assertThat(targets).extracting(BankAccountRefDto::accountNo).containsExactly("KB-0001");
  }

  @Test
  void cancelOwnBookingRequest_delegatesWithTheEchoedVersion() {
    UUID requestId = UUID.randomUUID();
    BankActor requester = TestBankActor.of(UUID.randomUUID()).build();
    actingAs(requester);

    requestService.cancelOwnBookingRequest(requestId, 4L);

    verify(bankBookingRequestService).cancelOwn(requester, requestId, 4L);
  }

  @Test
  void revokeOwnerApproval_byAdmin_delegatesARevoke() {
    UUID requestId = UUID.randomUUID();
    BankBookingRequest bookingRequest = new BankBookingRequest();
    bookingRequest.setAccount(
        account(UUID.randomUUID(), "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN")));
    bookingRequest.setStatus(BankBookingRequestStatus.PENDING);
    bookingRequest.setRequiresOwnerApproval(true);
    when(bankBookingRequestRepository.findByIdForUpdate(requestId))
        .thenReturn(Optional.of(bookingRequest));
    actingAs(TestBankActor.of(UUID.randomUUID()).admin());

    requestService.revokeOwnerApproval(requestId);

    verify(bankBookingRequestService).applyOwnerApprovalWithinTransaction(bookingRequest, false);
  }
}
