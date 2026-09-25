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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountApprovalLimit;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBalancePointDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankAccountBalance;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountApprovalLimitRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountViewGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BereichRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link OrgUnitBankAccessService} (REQ-BANK-021/-027/-028/-035..038). Covers the
 * card list ({@code canView} per account type), the read-only drill-in with Halter redaction, the
 * balance-target gate, and a visibility grant. The derived responsible-holder reverse-resolution
 * and its change-audit moved to {@link OrgUnitBankResponsibilityServiceTest} with their service.
 * Lenient strictness keeps the shared per-test stubs (e.g. {@code isAdmin=false}, empty grant
 * batch) from tripping the unnecessary-stubbing check across the many independent scenarios.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgUnitBankAccessServiceTest {

  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuthHelperService authHelperService;
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

  private OrgUnitBankAccessService service;

  @BeforeEach
  void defaultStubs() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(viewGrantRepository.findByAccountIdIn(anyCollection())).thenReturn(List.of());
    when(viewGrantRepository.findByAccountId(any())).thenReturn(List.of());
    when(approvalLimitRepository.findByAccountIdIn(anyCollection())).thenReturn(List.of());
    when(approvalLimitRepository.findByAccountId(any())).thenReturn(List.of());
    when(bankPostingRepository.postingSlicesSince(anyCollection(), any())).thenReturn(List.of());
    wireDelegates();
  }

  /**
   * Wires the L3-split (#922) write-mechanics collaborators into the {@link
   * OrgUnitBankAccessService} facade under test. Mockito does not inject one {@code @InjectMocks}
   * target into another, so the facade's two collaborator fields are built here as REAL instances
   * fed with the same mocks the scenarios stub (view-grant repo, approval-limit repo, user repo,
   * audit service, and — for the limit row lock — the bank-account repo), then set via {@link
   * ReflectionTestUtils}. The facade still loads + authorizes + validates and re-reads the settings
   * snapshot; the collaborators run the actual grant/revoke and upsert/clear against those mocks,
   * so the existing {@code verify(...)} assertions on save / delete / audit hold unchanged.
   * Constructor-arg order matches each service's {@code @RequiredArgsConstructor} field-declaration
   * order.
   */
  private void wireDelegates() {
    OrgUnitBankVisibilityService visibilityService =
        new OrgUnitBankVisibilityService(viewGrantRepository, userRepository, bankAuditService);
    OrgUnitBankApprovalLimitService approvalLimitService =
        new OrgUnitBankApprovalLimitService(
            bankAccountRepository, approvalLimitRepository, userRepository, bankAuditService);
    service =
        new OrgUnitBankAccessService(
            ownerScopeService,
            authHelperService,
            bankAccountRepository,
            bankPostingRepository,
            viewGrantRepository,
            approvalLimitRepository,
            bankBookingRequestRepository,
            bereichRepository,
            userRepository,
            bankAccountService,
            bankApprovalLimitService,
            bankStatementReportService,
            bankBookingRequestService,
            bankAuditService,
            visibilityService,
            approvalLimitService);
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

  private static OrgUnit specialCommand(UUID id, String name, String shorthand) {
    SpecialCommand sk = new SpecialCommand();
    sk.setId(id);
    sk.setName(name);
    sk.setShorthand(shorthand);
    return sk;
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(ownStaffelId)));
    when(ownerScopeService.currentOwnLevelOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(ownStaffelId)));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(authHelperService.isMemberOrAbove()).thenReturn(true);
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(
            List.of(account(UUID.randomUUID(), "KB-0001", squadron(UUID.randomUUID(), "S", "S"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result).isEmpty();
    verify(bankPostingRepository, never()).accountBalances(anyCollection());
  }

  @Test
  void listOverseenOrgUnitBalances_adminSeesEveryOrgUnitAccountAndZeroWhenNoPostings() {
    when(authHelperService.isAdmin()).thenReturn(true);
    UUID accountAId = UUID.randomUUID();
    UUID accountBId = UUID.randomUUID();
    BankAccount accountA = account(accountAId, "KB-0001", squadron(UUID.randomUUID(), "A", "AAA"));
    BankAccount accountB = account(accountBId, "KB-0002", squadron(UUID.randomUUID(), "B", "BBB"));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(ownerScopeService.currentOwnLevelOversightScope())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(ownerScopeService.currentOwnLevelOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(viewGrantRepository.findByAccountIdIn(anyCollection())).thenReturn(List.of(grant));
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(staffelId, MembershipRole.ENSIGN))
        .thenReturn(true);
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(ownerScopeService.currentOwnLevelOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(authHelperService.isMemberOrAbove()).thenReturn(true);
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(ownerScopeService.currentOwnLevelOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(bereichRepository.findByDepartment(any())).thenReturn(List.of(profit));
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(
            profitBereichId, MembershipRole.BEREICHSLEITER))
        .thenReturn(true);
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(ownerScopeService.currentOwnLevelOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(ownerScopeService.currentUserIsBereichsleiter()).thenReturn(true);
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(staffelId)));
    when(ownerScopeService.currentOwnLevelOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(staffelId)));
    when(ownerScopeService.currentUserIsOlMember()).thenReturn(false);
    when(ownerScopeService.currentUserIsBereichsleiter()).thenReturn(false);
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(List.of(ownAccount, special));
    when(bankPostingRepository.accountBalances(List.of(ownAccountId)))
        .thenReturn(List.of(new BankAccountBalance(ownAccountId, new BigDecimal("10"))));

    List<OrgUnitBankBalanceDto> result = service.listOverseenOrgUnitBalances();

    assertThat(result).extracting(OrgUnitBankBalanceDto::accountId).containsExactly(ownAccountId);
  }

  @Test
  void getViewableAccountBookings_redactsHolderHandles() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(staffelId)));
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
    assertThat(redacted.note()).isEqualTo("note");
    assertThat(redacted.justification()).isEqualTo("Reparatur");
  }

  @Test
  void getViewableAccountBookings_deniedWhenCallerMayNotView() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));

    assertThrows(
        AccessDeniedException.class,
        () -> service.getViewableAccountBookings(accountId, PageRequest.of(0, 20), null, null));
    verifyNoInteractions(bankStatementReportService);
  }

  @Test
  void getViewableBalanceSeries_delegatesForViewableAccount() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(staffelId)));
    Instant from = Instant.parse("2026-06-01T00:00:00Z");
    Instant to = Instant.parse("2026-07-01T00:00:00Z");
    BankBalanceSeriesDto expected =
        new BankBalanceSeriesDto(
            List.of(new BankBalancePointDto(from, new BigDecimal("100"))), new BigDecimal("500"));
    when(bankAccountService.getBalanceSeries(accountId, from, to)).thenReturn(expected);

    assertThat(service.getViewableBalanceSeries(accountId, from, to)).isSameAs(expected);
  }

  @Test
  void getViewableBalanceSeries_deniedWhenCallerMayNotView() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));

    assertThrows(
        AccessDeniedException.class,
        () -> service.getViewableBalanceSeries(accountId, Instant.now(), Instant.now()));
    verifyNoInteractions(bankStatementReportService);
  }

  @Test
  void getViewableAccountDetail_deniedWhenCallerMayNotView() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(UUID.randomUUID(), "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));

    assertThrows(AccessDeniedException.class, () -> service.getViewableAccountDetail(accountId));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(staffelId)));
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
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(staffelId, MembershipRole.STAFFELLEITER))
        .thenReturn(true);
    when(bankAccountRepository.saveAndFlush(account)).thenReturn(account);

    var settings = service.setBalanceTarget(accountId, new BigDecimal("10000"), 3L);

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
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(staffelId, MembershipRole.STAFFELLEITER))
        .thenReturn(false);

    assertThrows(
        AccessDeniedException.class,
        () -> service.setBalanceTarget(accountId, new BigDecimal("10000"), 1L));
    verify(bankAccountRepository, never()).saveAndFlush(any());
  }

  @Test
  void setBalanceTarget_versionMismatch_throwsOptimisticLock() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    account.setVersion(5L);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(staffelId, MembershipRole.STAFFELLEITER))
        .thenReturn(true);

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.setBalanceTarget(accountId, new BigDecimal("1"), 4L));
  }

  @Test
  void admin_maySetTargetAndConfigureVisibilityOnAnyAccountWithoutBeingHolder() {
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    account.setVersion(1L);
    when(authHelperService.isAdmin()).thenReturn(true);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(bankAccountRepository.saveAndFlush(account)).thenReturn(account);
    when(viewGrantRepository.existsByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, MembershipRole.ENSIGN.name()))
        .thenReturn(false);
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(staffelId, MembershipRole.STAFFELLEITER))
        .thenReturn(false);

    service.setBalanceTarget(accountId, new BigDecimal("100"), 1L);
    service.addRoleVisibility(accountId, MembershipRole.ENSIGN.name());

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
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(staffelId, MembershipRole.STAFFELLEITER))
        .thenReturn(true);
    when(viewGrantRepository.existsByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, MembershipRole.ENSIGN.name()))
        .thenReturn(false);

    service.addRoleVisibility(accountId, MembershipRole.ENSIGN.name());

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
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(staffelId, MembershipRole.STAFFELLEITER))
        .thenReturn(true);

    assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
        () -> service.addRoleVisibility(accountId, MembershipRole.BEREICHSKOORDINATOR.name()));
    verify(viewGrantRepository, never()).save(any());
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
            eq(accountId),
            eq(BankBookingRequestType.DEPOSIT),
            eq(new BigDecimal("500")),
            eq("from sale"),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            isNull(),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(expected);

    assertThat(service.createBookingRequest(request)).isSameAs(expected);
    verifyNoInteractions(ownerScopeService);
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
            eq(accountId),
            eq(BankBookingRequestType.DEPOSIT),
            eq(new BigDecimal("1000")),
            eq("from sale"),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            isNull(),
            eq(true),
            eq(new BigDecimal("30")),
            eq(null),
            eq(null)))
        .thenReturn(expected);

    assertThat(service.createBookingRequest(request)).isSameAs(expected);
    verifyNoInteractions(ownerScopeService);
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
            eq(accountId),
            eq(BankBookingRequestType.DEPOSIT),
            eq(new BigDecimal("500")),
            eq("from sale"),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            isNull(),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(expected);

    assertThat(service.createBookingRequest(request)).isSameAs(expected);
    verifyNoInteractions(ownerScopeService);
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
            eq(accountId),
            eq(BankBookingRequestType.DEPOSIT),
            eq(new BigDecimal("250")),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            isNull(),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(expected);

    assertThat(service.createBookingRequest(request)).isSameAs(expected);
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
            eq(accountId),
            eq(BankBookingRequestType.DEPOSIT),
            eq(new BigDecimal("5000")),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            isNull(),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, orgUnitId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.DEPOSIT),
            eq(new BigDecimal("5000")),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            isNull(),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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

    assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
        () -> service.createBookingRequest(request));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));

    assertThrows(AccessDeniedException.class, () -> service.createBookingRequest(request));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(orgUnitId)));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(limit));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(caller));
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("100")),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, orgUnitId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("100")),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(orgUnitId)));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of());
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(null),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, orgUnitId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(null),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(orgUnitId)));
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.TRANSFER),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(destId),
            eq(true),
            eq(null),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, orgUnitId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.TRANSFER),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(destId),
            eq(true),
            eq(null),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(viewGrantRepository.findByAccountId(accountId)).thenReturn(List.of(viewGrant));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(allMembers));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(outsider));
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(null),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, orgUnitId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(null),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
    verify(ownerScopeService).currentUserIsMemberOfOrgUnit(orgUnitId);
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(orgUnitId)));
    when(viewGrantRepository.findByAccountId(accountId)).thenReturn(List.of());
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(allMembers));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(member));
    when(ownerScopeService.currentUserIsMemberOfOrgUnit(orgUnitId)).thenReturn(true);
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("100")),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, orgUnitId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("100")),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
  }

  /**
   * Builds an active KRT (CARTEL) account with the two approval-ladder thresholds set, viewable via
   * {@code isMemberOrAbove}, and stubs the common request-create preconditions.
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(viewGrantRepository.findByAccountId(accountId)).thenReturn(List.of());
    when(authHelperService.isMemberOrAbove()).thenReturn(true);
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
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq("reason"),
            eq(null),
            eq(null),
            eq(false),
            eq(new BigDecimal("1000")),
            isNull(),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq("reason"),
            eq(null),
            eq(null),
            eq(false),
            eq(new BigDecimal("1000")),
            isNull(),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
  }

  @Test
  void createBookingRequest_krtMiddleBand_routesToBereichsleiterProfit() {
    UUID accountId = UUID.randomUUID();
    krtAccountWithTiers(accountId, "1000", "5000");
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("3000"), "reason");
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("3000")),
            eq("reason"),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.BANK_MANAGEMENT),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("3000")),
            eq("reason"),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.BANK_MANAGEMENT),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
  }

  @Test
  void createBookingRequest_krtTopBand_routesToOrganisationsleitung() {
    UUID accountId = UUID.randomUUID();
    krtAccountWithTiers(accountId, "1000", "5000");
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("9000"), "reason");
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("9000")),
            eq("reason"),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.ORGANISATIONSLEITUNG),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("9000")),
            eq("reason"),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.ORGANISATIONSLEITUNG),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(orgUnitId)));
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(true);
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("2000000")),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, orgUnitId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("2000000")),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(orgUnitId)));
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(true);
    when(bankBookingRequestService.create(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            any(),
            anyBoolean(),
            any(),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, orgUnitId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("2000000")),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
  }

  @Test
  void createBookingRequest_krtByOlMember_bypassesAmountLadder() {
    UUID accountId = UUID.randomUUID();
    krtAccountWithTiers(accountId, "1000", "5000");
    when(ownerScopeService.currentUserIsOlMember()).thenReturn(true);
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("9000"), "reason");
    when(bankBookingRequestService.create(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            any(),
            anyBoolean(),
            any(),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("9000")),
            eq("reason"),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(null),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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

    assertThrows(AccessDeniedException.class, () -> service.grantOwnerApproval(requestId));
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
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(true);
    when(bankBookingRequestService.applyOwnerApprovalWithinTransaction(bookingRequest, true))
        .thenReturn(requestDto(accountId, orgUnitId));

    BankBookingRequestDto dto = service.grantOwnerApproval(requestId);

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
    when(authHelperService.isAdmin()).thenReturn(true);
    when(bankBookingRequestRepository.findByIdForUpdate(requestId))
        .thenReturn(Optional.of(bookingRequest));
    when(bankBookingRequestService.applyOwnerApprovalWithinTransaction(bookingRequest, true))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    BankBookingRequestDto dto = service.grantOwnerApproval(requestId);

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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(viewGrantRepository.findByAccountId(accountId)).thenReturn(List.of());
    when(authHelperService.isMemberOrAbove()).thenReturn(true);
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            accountId, BankBookingRequestType.WITHDRAWAL, null, new BigDecimal("9000"), "reason");
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("9000")),
            eq("reason"),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.BANK_MANAGEMENT),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("9000")),
            eq("reason"),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.BANK_MANAGEMENT),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("3000")),
            eq("note"),
            eq("reason"),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.BANK_MANAGEMENT),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    service.raiseCartelDirectBookingRequest(
        accountId,
        BankBookingRequestType.WITHDRAWAL,
        new BigDecimal("3000"),
        "note",
        "reason",
        null);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("3000")),
            eq("note"),
            eq("reason"),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.BANK_MANAGEMENT),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("9000")),
            eq(null),
            eq("reason"),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.ORGANISATIONSLEITUNG),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, UUID.randomUUID()));

    service.raiseCartelDirectBookingRequest(
        accountId, BankBookingRequestType.WITHDRAWAL, new BigDecimal("9000"), null, "reason", null);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("9000")),
            eq(null),
            eq("reason"),
            eq(null),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.ORGANISATIONSLEITUNG),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(orgUnitId)));
    when(approvalLimitRepository.findByAccountId(accountId))
        .thenReturn(List.of(roleLimit, userLimit));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(caller));
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(orgUnitId, MembershipRole.ENSIGN))
        .thenReturn(true);
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("100")),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, orgUnitId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(true),
            eq(new BigDecimal("100")),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
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
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(bereichId)));
    when(approvalLimitRepository.findByAccountId(accountId))
        .thenReturn(List.of(roleLimit, areaMembers));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(caller));
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(
            bereichId, MembershipRole.BEREICHSKOORDINATOR))
        .thenReturn(true);
    when(ownerScopeService.currentUserIsMemberOfAreaCascade(bereichId)).thenReturn(true);
    when(bankBookingRequestService.create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(new BigDecimal("1000")),
            isNull(),
            eq(false),
            eq(null),
            eq(null),
            eq(null)))
        .thenReturn(requestDto(accountId, bereichId));

    service.createBookingRequest(request);

    verify(bankBookingRequestService)
        .create(
            eq(accountId),
            eq(BankBookingRequestType.WITHDRAWAL),
            eq(new BigDecimal("500")),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(new BigDecimal("1000")),
            isNull(),
            eq(false),
            eq(null),
            eq(null),
            eq(null));
  }

  /**
   * REQ-BANK-054: the requester's own list is the member audience, so the seam blanks the bank
   * employee's "Notiz Bankmitarbeiter" before it can reach a template. Pinning it here rather than
   * only in the view keeps the redaction out of the rendering layer's hands — the same DTO type
   * carries the note unredacted for the approval tab.
   */
  @Test
  void listOwnBookingRequests_redactsTheStaffNote() {
    UUID accountId = UUID.randomUUID();
    BankBookingRequestDto withNote =
        requestDto(accountId, UUID.randomUUID(), "Bar uebergeben, Zeuge greluc");
    when(bankBookingRequestService.listForCurrentRequester()).thenReturn(List.of(withNote));

    List<BankBookingRequestDto> own = service.listOwnBookingRequests();

    assertThat(own).hasSize(1);
    assertThat(own.getFirst().staffNote()).isNull();
    assertThat(own.getFirst().note()).isEqualTo("from sale");
    assertThat(own.getFirst().id()).isEqualTo(withNote.id());
    assertThat(own.getFirst().version()).isEqualTo(withNote.version());
  }

  /**
   * The approver lens is deliberately NOT redacted: a responsible holder decides on the request and
   * the employee's note is part of that context (REQ-BANK-054). This is the negative control for
   * {@link #listOwnBookingRequests_redactsTheStaffNote()} — without it, a blanket redaction in the
   * shared {@code toDto} would pass that test while silently emptying the approval tab.
   */
  @Test
  void listRequestsForResponsibleAccounts_keepsTheStaffNote() {
    UUID accountId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    when(authHelperService.isAdmin()).thenReturn(true);
    when(bankAccountRepository.findAllByOrderByAccountNoAsc())
        .thenReturn(List.of(account(accountId, "KB-0001", null)));
    when(bankBookingRequestService.listForAccounts(anyCollection()))
        .thenReturn(List.of(requestDto(accountId, orgUnitId, "Bar uebergeben, Zeuge greluc")));

    List<BankBookingRequestDto> foreign = service.listRequestsForResponsibleAccounts();

    assertThat(foreign)
        .singleElement()
        .extracting(BankBookingRequestDto::staffNote)
        .isEqualTo("Bar uebergeben, Zeuge greluc");
  }

  /**
   * REQ-BANK-056, the security-critical path: an edit re-derives the approval snapshot from the NEW
   * amount through the very same resolution the create path uses. Raising a request from below the
   * requester's ceiling to above it must flip {@code requiresOwnerApproval} to true — otherwise a
   * requester could file 100 aUEC under a 1000 limit and then edit it to 100000, arriving at the
   * bank employee still carrying the original "no approval needed" snapshot.
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
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(allMembers));
    when(ownerScopeService.currentUserIsMemberOfOrgUnit(orgUnitId)).thenReturn(true);

    UpdateBankBookingRequest update =
        new UpdateBankBookingRequest(
            new BigDecimal("100000"), null, "reason", null, null, null, 0L);
    service.updateOwnBookingRequest(requestId, update);

    verify(bankBookingRequestService)
        .updateOwn(
            eq(requestId),
            eq(update),
            eq(true),
            eq(new BigDecimal("1000")),
            eq(BankRequestApprover.RESPONSIBLE_HOLDER));
  }

  /**
   * The mirror case: an edit that stays under the ceiling leaves the request approval-free, so a
   * correction of a typo does not gratuitously drag a responsible holder into the loop.
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
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));
    when(approvalLimitRepository.findByAccountId(accountId)).thenReturn(List.of(allMembers));
    when(ownerScopeService.currentUserIsMemberOfOrgUnit(orgUnitId)).thenReturn(true);

    UpdateBankBookingRequest update =
        new UpdateBankBookingRequest(new BigDecimal("900"), null, "reason", null, null, null, 0L);
    service.updateOwnBookingRequest(requestId, update);

    verify(bankBookingRequestService)
        .updateOwn(eq(requestId), eq(update), eq(false), eq(new BigDecimal("1000")), isNull());
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
    when(authHelperService.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));

    assertThrows(
        NotFoundException.class,
        () ->
            service.updateOwnBookingRequest(
                requestId,
                new UpdateBankBookingRequest(
                    new BigDecimal("900"), null, "reason", null, null, null, 0L)));
    verify(bankBookingRequestService, never()).updateOwn(any(), any(), anyBoolean(), any(), any());
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
        0L);
  }
}
