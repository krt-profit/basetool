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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.BankAccountBalance;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountApprovalLimitRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountViewGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BereichRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
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
import org.mockito.InjectMocks;
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
 * Unit tests for {@link OrgUnitBankAccessService} (REQ-BANK-021/-027/-028/-034..038). Covers the
 * card list ({@code canView} per account type), the derived responsible-holder resolution, the
 * read-only drill-in with Halter redaction, the balance-target gate, and a visibility grant.
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
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private UserRepository userRepository;
  @Mock private BankAccountService bankAccountService;
  @Mock private BankApprovalLimitService bankApprovalLimitService;
  @Mock private BankStatementReportService bankStatementReportService;
  @Mock private BankBookingRequestService bankBookingRequestService;
  @Mock private BankAuditService bankAuditService;

  @InjectMocks private OrgUnitBankAccessService service;

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
    ReflectionTestUtils.setField(service, "orgUnitBankVisibilityService", visibilityService);
    ReflectionTestUtils.setField(service, "orgUnitBankApprovalLimitService", approvalLimitService);
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
    // REQ-BANK-035: a member with no oversight still sees an account where the holder granted their
    // membership role on the owning unit.
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
    // REQ-BANK-039: eligibility = view eligibility, so a member who may view a request-capable
    // account may now also request against it (previously own-level oversight only).
    assertThat(result.getFirst().canRequest()).isTrue();
  }

  @Test
  void listOverseenOrgUnitBalances_cartelAccountVisibleToAnyMember() {
    // REQ-BANK-037: the CARTEL/KRT account is always visible to every KRT member.
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
    // REQ-BANK-037: CARTEL_BANK is held by the Profit-Bereichsleiter; a non-holder does not see it.
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
    // REQ-BANK-037 (tightened REQ-BANK-028): Sonderkonten auto-visible to OL members and
    // Bereichsleiter; an officer (squadron rank only) does not see them.
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
    // REQ-BANK-038: the player-custody columns are nulled before they cross the wire.
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
            Instant.now(),
            null,
            "KB-0002",
            "Other",
            "counterHolder",
            false,
            BigDecimal.ZERO,
            "carol",
            "Staffel Rot");
    when(bankAccountService.getBookings(eq(accountId), any()))
        .thenReturn(new PageImpl<>(List.of(raw)));

    var page = service.getViewableAccountBookings(accountId, PageRequest.of(0, 20));

    BankBookingDto redacted = page.getContent().getFirst();
    assertThat(redacted.holderHandle()).isNull();
    assertThat(redacted.counterHolderHandle()).isNull();
    // REQ-BANK-044: the deposit/withdrawal counterparty is player-identifying too, so it is
    // redacted alongside the holder columns for org-unit viewers.
    assertThat(redacted.counterpartyHandle()).isNull();
    assertThat(redacted.counterpartyOrgUnitName()).isNull();
    assertThat(redacted.counterAccountNo()).isEqualTo("KB-0002");
    assertThat(redacted.amount()).isEqualByComparingTo("-100");
    // REQ-BANK-045: the Begründung is not player-identifying, so it survives redaction like the
    // note.
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
        () -> service.getViewableAccountBookings(accountId, PageRequest.of(0, 20)));
    verifyNoInteractions(bankStatementReportService);
  }

  @Test
  void getViewableAccountDetail_deniedWhenCallerMayNotView() {
    // The backend endpoint is only isAuthenticated()-gated (it diverges from the page controller's
    // MEMBER_OR_ABOVE gate), so a non-member — e.g. a GUEST hitting GET
    // /api/v1/org-units/bank/accounts/{id} directly — must be stopped by the seam's canView guard
    // before any account detail is loaded.
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
    // REQ-BANK-038: the seam authorizes view access, then asks for the Halter-redacted variant.
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
    // The admin override: an admin who is not the responsible holder may still set the balance
    // target and configure the visibility of a Staffel account.
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
    // The admin holds no STAFFELLEITER role on the unit.
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

    // A squadron account has no BEREICHSKOORDINATOR bucket.
    assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
        () -> service.addRoleVisibility(accountId, MembershipRole.BEREICHSKOORDINATOR.name()));
    verify(viewGrantRepository, never()).save(any());
  }

  @Test
  void createBookingRequest_viewableAccount_resolvesAndDelegates() {
    // REQ-BANK-042: a deposit delegates straight through with no limit and no owner approval; it
    // bypasses the view gate entirely (the oversight scope is never consulted for a deposit).
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
            eq(null)))
        .thenReturn(expected);

    assertThat(service.createBookingRequest(request)).isSameAs(expected);
    verifyNoInteractions(ownerScopeService);
  }

  @Test
  void createBookingRequest_splitDeposit_passesSplitSnapshotToCreate() {
    // REQ-BANK-043: a split deposit request forwards split_enabled + split_percent to the
    // org-unit-blind create(); the split is DEPOSIT-only and never approval-limited.
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
            new BigDecimal("30"));
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
            eq(new BigDecimal("30"))))
        .thenReturn(expected);

    assertThat(service.createBookingRequest(request)).isSameAs(expected);
    verifyNoInteractions(ownerScopeService);
  }

  @Test
  void createBookingRequest_depositOnUnviewableAccount_succeedsWithoutApprovalOrLimit() {
    // REQ-BANK-042: a deposit is requestable against ANY active account by ANY user — no view gate,
    // no oversight consult and never approval-limited (requiresOwnerApproval=false, limit=null).
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
            eq(null)))
        .thenReturn(expected);

    assertThat(service.createBookingRequest(request)).isSameAs(expected);
    // No view-eligibility check (oversight scope) and no approval-limit lookup for a deposit.
    verifyNoInteractions(ownerScopeService);
    verify(approvalLimitRepository, never()).findByAccountId(any());
  }

  @Test
  void createBookingRequest_depositOnSpecialAccount_succeeds() {
    // REQ-BANK-042: a deposit may target a non-request-capable type (SPECIAL / CARTEL_BANK) too —
    // isRequestCapable is not consulted for a deposit.
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
            eq(null)))
        .thenReturn(expected);

    assertThat(service.createBookingRequest(request)).isSameAs(expected);
  }

  @Test
  void createBookingRequest_depositAboveConfiguredLimit_neverFlagsApproval() {
    // REQ-BANK-042: even with a user limit far below the amount, a deposit is never flagged and the
    // limit rows are not consulted.
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
            eq(null));
    verify(approvalLimitRepository, never()).findByAccountId(any());
  }

  @Test
  void createBookingRequest_depositWithDestination_throwsBadRequest() {
    // REQ-BANK-042/-040: a deposit must not carry a destination account.
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
    // REQ-BANK-041: an individual-user limit below the requested amount flags the request.
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
            eq(null));
  }

  @Test
  void createBookingRequest_withdrawalNoLimit_alwaysFlagsRequiresOwnerApproval() {
    // REQ-BANK-041 (amended): when NO approval limit applies to the requester (no user, role or
    // all-members limit configured), a withdrawal/transfer ALWAYS needs the responsible holder's
    // approval — requiresOwnerApproval=true with applicableLimit=null. (Before the amendment a
    // missing limit meant unlimited / never-needs-approval.)
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
            eq(null));
  }

  @Test
  void createBookingRequest_transfer_passesDestination() {
    // REQ-BANK-040: a transfer carries its destination account through to the request service.
    // REQ-BANK-041 (amended): no approval limit is configured for this account, so the request now
    // always needs the responsible holder's approval (requiresOwnerApproval=true, limit=null).
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
            eq(null));
  }

  @Test
  void createBookingRequest_nonMemberViewGrantHolder_notCappedByAllMembers_needsApproval() {
    // REQ-BANK-047 (amends REQ-BANK-041): the all-members ceiling is now MEMBERS-ONLY. An outsider
    // holding only a USER *view* grant (canView via the grant, but NOT a member of the owning unit
    // and matching no USER/role limit) is NOT covered by the all-members limit — they fall through
    // to approval-required (applicableLimit = null, requiresOwnerApproval = true,
    // RESPONSIBLE_HOLDER).
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
    // The outsider is NOT a member of the owning unit (currentUserIsMemberOfOrgUnit defaults
    // false),
    // so the ALL_MEMBERS ceiling does not apply.
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
            eq(null));
    verify(ownerScopeService).currentUserIsMemberOfOrgUnit(orgUnitId);
  }

  @Test
  void createBookingRequest_allMembersLimit_appliesToActualOwningUnitMember() {
    // REQ-BANK-047: the ALL_MEMBERS ceiling now applies ONLY to an actual member of the owning
    // unit.
    // A member requesting above the ceiling is flagged for the responsible holder's approval.
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
    // REQ-BANK-047: a KRT withdrawal at or below T1 needs no external approval; the bank employee
    // self-approves. applicable_limit is T1 (display), required_approver null.
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
            eq(null));
  }

  @Test
  void createBookingRequest_krtMiddleBand_routesToBereichsleiterProfit() {
    // REQ-BANK-047: a KRT withdrawal above T1 and at/below T2 needs the Bereichsleiter Profit.
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
            eq(BankRequestApprover.AREA_LEAD_PROFIT),
            eq(false),
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
            eq(BankRequestApprover.AREA_LEAD_PROFIT),
            eq(false),
            eq(null));
  }

  @Test
  void createBookingRequest_krtTopBand_routesToOrganisationsleitung() {
    // REQ-BANK-047: a KRT withdrawal above T2 needs the Organisationsleitung.
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
            eq(null));
  }

  @Test
  void grantOwnerApproval_byNonResponsibleHolder_throwsAccessDenied() {
    // Security review (S1): only the account's responsible holder (or an admin) may grant the
    // in-app owner approval; a non-responsible, non-admin caller is rejected before any delegation.
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
    // Security review (S1): the responsible holder (Staffelleiter of the owning unit) passes the
    // guard and the seam delegates the blind mutation to the booking-request service.
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
  void resolveResponsibleHolderUserIds_staffelAccount_returnsStaffelleiter() {
    // REQ-BANK-034/-026: the reverse resolution for the notification engine — a Staffelkonto's
    // responsible holders are the STAFFELLEITER of the owning Staffel.
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID leiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(Set.of(leiter));

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).containsExactly(leiter);
  }

  @Test
  void resolveResponsibleHolderUserIds_skAccount_returnsSkLead() {
    // REQ-BANK-034: an SK-Konto's responsible holder is the SK_LEAD of the owning Spezialkommando.
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID skLead = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0002", specialCommand(orgUnitId, "SK", "SK"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(orgUnitId, MembershipRole.SK_LEAD))
        .thenReturn(Set.of(skLead));

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).containsExactly(skLead);
  }

  @Test
  void resolveResponsibleHolderUserIds_cartelAccount_returnsAllOlMembers() {
    // REQ-BANK-034: the CARTEL/KRT account is held collegially by all OL members.
    UUID olId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID ol1 = UUID.randomUUID();
    UUID ol2 = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    ol.setName("OL");
    BankAccount cartel = typedAccount(accountId, "KB-0003", BankAccountType.CARTEL, ol);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(cartel));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(olId, MembershipRole.OL_MEMBER))
        .thenReturn(Set.of(ol1, ol2));

    assertThat(service.resolveResponsibleHolderUserIds(accountId))
        .containsExactlyInAnyOrder(ol1, ol2);
  }

  @Test
  void resolveResponsibleHolderUserIds_cartelBank_returnsProfitBereichsleiter() {
    // REQ-BANK-034: the Kartellbankkonto's responsible holder is the BEREICHSLEITER of every PROFIT
    // Bereich, unioned.
    UUID profitBereichId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID bl = UUID.randomUUID();
    Bereich profit = new Bereich();
    profit.setId(profitBereichId);
    profit.setName("Profit");
    BankAccount cartelBank = typedAccount(accountId, "KB-0004", BankAccountType.CARTEL_BANK, null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(cartelBank));
    when(bereichRepository.findByDepartment(any())).thenReturn(List.of(profit));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            profitBereichId, MembershipRole.BEREICHSLEITER))
        .thenReturn(Set.of(bl));

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).containsExactly(bl);
  }

  @Test
  void resolveResponsibleHolderUserIds_specialAccount_returnsEmptyWithoutLookup() {
    // REQ-BANK-034: a Sonderkonto has no responsible holder — empty, and no membership lookup runs.
    UUID accountId = UUID.randomUUID();
    BankAccount special = specialAccount(accountId, "KB-0009", BankAccountStatus.ACTIVE);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(special));

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).isEmpty();
    verifyNoInteractions(orgUnitMembershipRepository);
  }

  @Test
  void resolveResponsibleHolderUserIds_missingAccount_returnsEmpty() {
    UUID accountId = UUID.randomUUID();
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.empty());

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).isEmpty();
  }

  private static BankBookingRequestDto requestDto(UUID accountId, UUID orgUnitId) {
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
        0L);
  }
}
