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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for {@link OrgUnitBankVisibleAccountsQuery} (REQ-BANK-064): the fixed backend order,
 * the in-memory paging with the true total and the per-account figures.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgUnitBankVisibleAccountsQueryTest {

  private static final UUID UNIT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID UNIT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID UNIT_OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

  @Mock private OrgUnitBankAccessService accessService;
  @Mock private BankPostingRepository bankPostingRepository;

  private OrgUnitBankVisibleAccountsQuery query;

  @BeforeEach
  void setUp() {
    when(bankPostingRepository.accountBalances(anyCollection())).thenReturn(List.of());
    when(bankPostingRepository.postingSlicesSince(anyCollection(), any())).thenReturn(List.of());
    query = new OrgUnitBankVisibleAccountsQuery(accessService, bankPostingRepository);
  }

  private static OrgUnit unit(UUID id) {
    Squadron squadron = new Squadron();
    squadron.setId(id);
    squadron.setName("Unit " + id);
    squadron.setShorthand("U");
    return squadron;
  }

  private static BankAccount account(String id, String accountNo, BankAccountType type, UUID unit) {
    BankAccount account = new BankAccount();
    account.setId(UUID.fromString(id));
    account.setAccountNo(accountNo);
    account.setName(accountNo + " account");
    account.setType(type);
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setOrgUnit(unit(unit));
    return account;
  }

  private void activeContext(Set<UUID> units) {
    when(accessService.activeContextOrgUnitIds()).thenReturn(units);
  }

  private static List<String> accountNos(Page<OrgUnitBankVisibleAccountDto> page) {
    return page.getContent().stream()
        .map(dto -> dto.accountNo() + "/" + dto.accountId().toString().substring(35))
        .toList();
  }

  @Test
  void ordersKrtFirstThenActiveContextThenRestByAccountNoAndId() {
    activeContext(Set.of(UNIT_A));
    when(accessService.listViewableActiveAccounts())
        .thenReturn(
            List.of(
                account(
                    "00000000-0000-0000-0000-000000000001",
                    "KB-0001",
                    BankAccountType.ORG_UNIT,
                    UNIT_OTHER),
                account(
                    "00000000-0000-0000-0000-000000000003",
                    "KB-0002",
                    BankAccountType.ORG_UNIT,
                    UNIT_OTHER),
                account(
                    "00000000-0000-0000-0000-000000000002",
                    "KB-0002",
                    BankAccountType.ORG_UNIT,
                    UNIT_OTHER),
                account(
                    "00000000-0000-0000-0000-000000000004",
                    "KB-0003",
                    BankAccountType.ORG_UNIT,
                    UNIT_A),
                account(
                    "00000000-0000-0000-0000-000000000005",
                    "KB-0005",
                    BankAccountType.ORG_UNIT,
                    UNIT_A),
                account(
                    "00000000-0000-0000-0000-000000000006",
                    "KB-0009",
                    BankAccountType.CARTEL,
                    UNIT_OTHER)));

    Page<OrgUnitBankVisibleAccountDto> page = query.listVisibleAccounts(PageRequest.of(0, 50));

    assertThat(accountNos(page))
        .containsExactly(
            "KB-0009/6", "KB-0003/4", "KB-0005/5", "KB-0001/1", "KB-0002/2", "KB-0002/3");
  }

  @Test
  void everyUnitOfTheActiveContextRanksBeforeTheRest() {
    activeContext(Set.of(UNIT_A, UNIT_B));
    when(accessService.listViewableActiveAccounts())
        .thenReturn(
            List.of(
                account(
                    "00000000-0000-0000-0000-000000000001",
                    "KB-0001",
                    BankAccountType.ORG_UNIT,
                    UNIT_OTHER),
                account(
                    "00000000-0000-0000-0000-000000000002",
                    "KB-0002",
                    BankAccountType.ORG_UNIT,
                    UNIT_A),
                account(
                    "00000000-0000-0000-0000-000000000003",
                    "KB-0003",
                    BankAccountType.AREA,
                    UNIT_B)));

    Page<OrgUnitBankVisibleAccountDto> page = query.listVisibleAccounts(PageRequest.of(0, 50));

    assertThat(accountNos(page)).containsExactly("KB-0002/2", "KB-0003/3", "KB-0001/1");
  }

  @Test
  void pinnedContext_ordersThePinnedUnitFirstWithoutDroppingTheOthers() {
    activeContext(Set.of(UNIT_B));
    when(accessService.listViewableActiveAccounts())
        .thenReturn(
            List.of(
                account(
                    "00000000-0000-0000-0000-000000000001",
                    "KB-0001",
                    BankAccountType.ORG_UNIT,
                    UNIT_A),
                account(
                    "00000000-0000-0000-0000-000000000002",
                    "KB-0002",
                    BankAccountType.ORG_UNIT,
                    UNIT_B)));

    Page<OrgUnitBankVisibleAccountDto> page = query.listVisibleAccounts(PageRequest.of(0, 50));

    assertThat(accountNos(page)).containsExactly("KB-0002/2", "KB-0001/1");
    assertThat(page.getTotalElements()).isEqualTo(2);
  }

  @Test
  void firstPageOfSizeThree_returnsTheFirstThreeAndTheTrueTotal_loadingFiguresOnlyForThem() {
    activeContext(Set.of());
    List<BankAccount> visible =
        List.of(
            account(
                "00000000-0000-0000-0000-000000000001",
                "KB-0001",
                BankAccountType.ORG_UNIT,
                UNIT_OTHER),
            account(
                "00000000-0000-0000-0000-000000000002",
                "KB-0002",
                BankAccountType.ORG_UNIT,
                UNIT_OTHER),
            account(
                "00000000-0000-0000-0000-000000000003",
                "KB-0003",
                BankAccountType.ORG_UNIT,
                UNIT_OTHER),
            account(
                "00000000-0000-0000-0000-000000000004",
                "KB-0004",
                BankAccountType.ORG_UNIT,
                UNIT_OTHER),
            account(
                "00000000-0000-0000-0000-000000000005",
                "KB-0005",
                BankAccountType.ORG_UNIT,
                UNIT_OTHER));
    when(accessService.listViewableActiveAccounts()).thenReturn(visible);

    Page<OrgUnitBankVisibleAccountDto> page = query.listVisibleAccounts(PageRequest.of(0, 3));

    assertThat(accountNos(page)).containsExactly("KB-0001/1", "KB-0002/2", "KB-0003/3");
    assertThat(page.getTotalElements()).isEqualTo(5);
    verify(bankPostingRepository)
        .accountBalances(
            List.of(visible.get(0).getId(), visible.get(1).getId(), visible.get(2).getId()));
  }

  @Test
  void pageBeyondTheEnd_isEmptyWithTheTrueTotalAndRunsNoFigureQuery() {
    activeContext(Set.of());
    when(accessService.listViewableActiveAccounts())
        .thenReturn(
            List.of(
                account(
                    "00000000-0000-0000-0000-000000000001",
                    "KB-0001",
                    BankAccountType.CARTEL,
                    UNIT_OTHER)));

    Page<OrgUnitBankVisibleAccountDto> page = query.listVisibleAccounts(PageRequest.of(2, 3));

    assertThat(page.getContent()).isEmpty();
    assertThat(page.getTotalElements()).isEqualTo(1);
    verify(bankPostingRepository, never()).accountBalances(anyCollection());
  }

  @Test
  void carriesBalanceTargetAndThirtyDayTrend() {
    activeContext(Set.of(UNIT_A));
    BankAccount account =
        account(
            "00000000-0000-0000-0000-000000000001", "KB-0001", BankAccountType.ORG_UNIT, UNIT_A);
    account.setBalanceTarget(new BigDecimal("5000"));
    UUID id = account.getId();
    when(accessService.listViewableActiveAccounts()).thenReturn(List.of(account));
    when(bankPostingRepository.accountBalances(List.of(id)))
        .thenReturn(List.of(new BankAccountBalance(id, new BigDecimal("1000"))));
    when(bankPostingRepository.postingSlicesSince(anyCollection(), any()))
        .thenReturn(List.of(new BankPostingSlice(id, Instant.now(), new BigDecimal("200"))));

    OrgUnitBankVisibleAccountDto dto =
        query.listVisibleAccounts(PageRequest.of(0, 50)).getContent().getFirst();

    assertThat(dto.orgUnitId()).isEqualTo(UNIT_A);
    assertThat(dto.balance()).isEqualByComparingTo("1000");
    assertThat(dto.balanceTarget()).isEqualByComparingTo("5000");
    assertThat(dto.delta30d()).isEqualByComparingTo("200");
    assertThat(dto.sparkline()).hasSize(BankTrendCalculator.WINDOW_DAYS);
    assertThat(dto.sparkline().getFirst()).isEqualByComparingTo("800");
    assertThat(dto.sparkline().getLast()).isEqualByComparingTo("1000");
  }
}
