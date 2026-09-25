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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.BankHolderPosting;
import de.greluc.krt.profit.basetool.backend.model.BankPosting;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@code BankLedgerService#bookSplitDeposit} (REQ-BANK-043): distribution across
 * active squadron accounts except the named one, with a single holder leg over the gross.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankLedgerSplitDepositTest {

  @Mock private BankAccountRepository accountRepository;
  @Mock private BankHolderRepository holderRepository;
  @Mock private BankTransactionRepository transactionRepository;
  @Mock private BankPostingRepository postingRepository;
  @Mock private BankHolderPostingRepository holderPostingRepository;
  @Mock private BankAuditService bankAuditService;
  @Mock private BankTransferFeeService transferFeeService;
  @Mock private AuthHelperService authHelperService;
  @Mock private UserRepository userRepository;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  private BankLedgerService bankLedgerService;

  private final Map<UUID, BankAccount> accountsById = new HashMap<>();

  /**
   * Assembles the ledger service with the real {@link BankPostingWriter} and {@link
   * BankBookingGuards} over mocked repositories.
   */
  @BeforeEach
  void setUp() {
    BankPostingWriter writer =
        new BankPostingWriter(
            accountRepository,
            holderRepository,
            transactionRepository,
            postingRepository,
            holderPostingRepository,
            authHelperService);
    BankBookingGuards guards =
        new BankBookingGuards(accountRepository, postingRepository, authHelperService);
    bankLedgerService =
        new BankLedgerService(
            accountRepository,
            transactionRepository,
            postingRepository,
            holderPostingRepository,
            bankAuditService,
            transferFeeService,
            userRepository,
            orgUnitMembershipQueryService,
            writer,
            guards);
  }

  /**
   * Wires the shared stubs and registers every supplied account for lookup; the named account is
   * not part of the squadron enumeration.
   *
   * @param holder the receiving holder
   * @param named the deposit's named account (the remainder target)
   * @param squadrons the active squadron accounts the enumeration returns
   */
  private void wire(BankHolder holder, BankAccount named, List<BankAccount> squadrons) {
    accountsById.put(named.getId(), named);
    squadrons.forEach(a -> accountsById.put(a.getId(), a));
    lenient().when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    lenient().when(holderRepository.findById(holder.getId())).thenReturn(Optional.of(holder));
    lenient()
        .when(
            accountRepository.findByTypeAndStatusOrderById(
                BankAccountType.ORG_UNIT, BankAccountStatus.ACTIVE))
        .thenReturn(squadrons);
    lenient()
        .when(accountRepository.findByIdForUpdate(any()))
        .thenAnswer(inv -> Optional.ofNullable(accountsById.get(inv.<UUID>getArgument(0))));
    lenient().when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void bookSplitDeposit_distributesSliceEvenly_namedKeepsRemainder_oneHolderLeg() {
    BankHolder holder = holder();
    BankAccount named = cartelAccount();
    List<BankAccount> squadrons =
        List.of(squadronAccount(), squadronAccount(), squadronAccount(), squadronAccount());
    wire(holder, named, squadrons);

    bankLedgerService.bookDeposit(
        new BankDepositRequest(
            named.getId(), holder.getId(), new BigDecimal("1000"), "sale", true, bd(30)));

    Map<UUID, BigDecimal> legs = capturedAccountLegs();
    assertEquals(5, legs.size(), "named + four squadron legs");
    assertEquals(0, legs.get(named.getId()).compareTo(new BigDecimal("700")));
    squadrons.forEach(s -> assertEquals(0, legs.get(s.getId()).compareTo(new BigDecimal("75"))));
    assertEquals(0, sum(legs.values()).compareTo(new BigDecimal("1000")), "legs sum to the gross");

    ArgumentCaptor<BankHolderPosting> holderCaptor =
        ArgumentCaptor.forClass(BankHolderPosting.class);
    verify(holderPostingRepository, times(1)).save(holderCaptor.capture());
    assertEquals(0, holderCaptor.getValue().getAmount().compareTo(new BigDecimal("1000")));
    assertEquals(holder.getId(), holderCaptor.getValue().getHolder().getId());

    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.DEPOSIT_SPLIT_BOOKED),
            eq(named.getId()),
            any(),
            isNull(),
            contains("split"));
  }

  @Test
  void bookSplitDeposit_distributesRemainderAUecLargestRemainder() {
    BankHolder holder = holder();
    BankAccount named = cartelAccount();
    List<BankAccount> squadrons = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      squadrons.add(squadronAccount());
    }
    wire(holder, named, squadrons);

    bankLedgerService.bookDeposit(
        new BankDepositRequest(
            named.getId(), holder.getId(), new BigDecimal("1000"), null, true, bd(33)));

    Map<UUID, BigDecimal> legs = capturedAccountLegs();
    long got48 =
        squadrons.stream()
            .filter(s -> legs.get(s.getId()).compareTo(new BigDecimal("48")) == 0)
            .count();
    long got47 =
        squadrons.stream()
            .filter(s -> legs.get(s.getId()).compareTo(new BigDecimal("47")) == 0)
            .count();
    assertEquals(1, got48, "one account absorbs the leftover aUEC");
    assertEquals(6, got47);
    assertEquals(0, legs.get(named.getId()).compareTo(new BigDecimal("670")));
    assertEquals(0, sum(legs.values()).compareTo(new BigDecimal("1000")));
  }

  @Test
  void bookSplitDeposit_hundredPercent_dropsNamedLeg() {
    BankHolder holder = holder();
    BankAccount named = cartelAccount();
    List<BankAccount> squadrons = List.of(squadronAccount(), squadronAccount());
    wire(holder, named, squadrons);

    bankLedgerService.bookDeposit(
        new BankDepositRequest(
            named.getId(), holder.getId(), new BigDecimal("1000"), null, true, bd(100)));

    Map<UUID, BigDecimal> legs = capturedAccountLegs();
    assertEquals(2, legs.size());
    assertFalse(legs.containsKey(named.getId()), "a 100 % split books no named leg");
    squadrons.forEach(s -> assertEquals(0, legs.get(s.getId()).compareTo(new BigDecimal("500"))));
  }

  @Test
  void bookSplitDeposit_excludesNamedAccountEvenWhenItIsASquadron() {
    BankHolder holder = holder();
    BankAccount named = squadronAccount();
    BankAccount other1 = squadronAccount();
    BankAccount other2 = squadronAccount();
    wire(holder, named, List.of(named, other1, other2));

    bankLedgerService.bookDeposit(
        new BankDepositRequest(
            named.getId(), holder.getId(), new BigDecimal("1000"), null, true, bd(20)));

    Map<UUID, BigDecimal> legs = capturedAccountLegs();
    assertEquals(3, legs.size());
    assertEquals(0, legs.get(named.getId()).compareTo(new BigDecimal("800")));
    assertEquals(0, legs.get(other1.getId()).compareTo(new BigDecimal("100")));
    assertEquals(0, legs.get(other2.getId()).compareTo(new BigDecimal("100")));
  }

  @Test
  void bookSplitDeposit_noActiveSquadronAccount_throwsNoTargets() {
    BankHolder holder = holder();
    BankAccount named = cartelAccount();
    wire(holder, named, List.of());

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookDeposit(
                    new BankDepositRequest(
                        named.getId(),
                        holder.getId(),
                        new BigDecimal("1000"),
                        null,
                        true,
                        bd(30))));
    assertEquals(BankConflictException.CODE_BANK_SPLIT_NO_TARGETS, ex.getCode());
    verify(postingRepository, never()).save(any());
  }

  @Test
  void bookSplitDeposit_sliceRoundsBelowOneAUec_throwsTooSmall() {
    BankHolder holder = holder();
    BankAccount named = cartelAccount();
    wire(holder, named, List.of(squadronAccount()));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.bookDeposit(
                    new BankDepositRequest(
                        named.getId(), holder.getId(), new BigDecimal("1"), null, true, bd(1))));
    assertEquals(BankConflictException.CODE_BANK_SPLIT_TOO_SMALL, ex.getCode());
    verify(postingRepository, never()).save(any());
  }

  /**
   * Captures every account leg the booking persisted, keyed by account id.
   *
   * @return account id → signed leg amount
   */
  private Map<UUID, BigDecimal> capturedAccountLegs() {
    ArgumentCaptor<BankPosting> captor = ArgumentCaptor.forClass(BankPosting.class);
    verify(postingRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    Map<UUID, BigDecimal> legs = new LinkedHashMap<>();
    captor.getAllValues().forEach(p -> legs.put(p.getAccount().getId(), p.getAmount()));
    return legs;
  }

  /**
   * Sums a collection of amounts.
   *
   * @param amounts the amounts to add
   * @return their sum
   */
  private static BigDecimal sum(Iterable<BigDecimal> amounts) {
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal a : amounts) {
      total = total.add(a);
    }
    return total;
  }

  /**
   * A whole-number percent as a {@link BigDecimal}.
   *
   * @param percent the whole percent
   * @return the percent as a BigDecimal
   */
  private static BigDecimal bd(int percent) {
    return BigDecimal.valueOf(percent);
  }

  /**
   * Builds an active receiving holder.
   *
   * @return the holder
   */
  private static BankHolder holder() {
    BankHolder holder = new BankHolder();
    holder.setId(UUID.randomUUID());
    holder.setHandle("custodian-" + UUID.randomUUID());
    holder.setActive(true);
    return holder;
  }

  /**
   * Builds an active {@code ORG_UNIT} account owned by a fresh {@link Squadron}.
   *
   * @return the squadron account
   */
  private static BankAccount squadronAccount() {
    UUID orgUnitId = UUID.randomUUID();
    OrgUnit squadron = new Squadron();
    squadron.setId(orgUnitId);
    squadron.setName("Staffel " + orgUnitId);
    squadron.setShorthand("S" + orgUnitId.toString().substring(0, 4));
    BankAccount account = new BankAccount();
    account.setId(UUID.randomUUID());
    account.setAccountNo("KB-" + orgUnitId.toString().substring(0, 4));
    account.setName("Staffelkonto");
    account.setType(BankAccountType.ORG_UNIT);
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setOrgUnit(squadron);
    return account;
  }

  /**
   * Builds an active {@code CARTEL} account (not a squadron — a typical named deposit target that
   * is excluded from the split set by type).
   *
   * @return the cartel account
   */
  private static BankAccount cartelAccount() {
    BankAccount account = new BankAccount();
    account.setId(UUID.randomUUID());
    account.setAccountNo("KB-CART");
    account.setName("KRT");
    account.setType(BankAccountType.CARTEL);
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setOrgUnit(null);
    return account;
  }
}
