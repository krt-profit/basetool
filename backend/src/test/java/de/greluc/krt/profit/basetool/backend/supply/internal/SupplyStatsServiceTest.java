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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/** The monthly donation statistics and the member's own totals (REQ-SUP-013). */
@ExtendWith(MockitoExtension.class)
class SupplyStatsServiceTest {

  @Mock private SupplyDonationRepository donationRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private UserRepository userRepository;
  @Mock private SupplySettingsService settingsService;
  @Mock private SupplyAccessPolicy accessPolicy;
  @Mock private AuthHelperService authHelperService;

  private SupplyStatsService service;
  private Squadron mine;
  private Squadron other;
  private final YearMonth now = YearMonth.now(ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    service =
        new SupplyStatsService(
            donationRepository,
            orgUnitRepository,
            userRepository,
            settingsService,
            accessPolicy,
            authHelperService);
    mine = unit("Alpha");
    other = unit("Bravo");
  }

  private static Squadron unit(String name) {
    Squadron unit = new Squadron();
    unit.setId(UUID.randomUUID());
    unit.setName(name);
    return unit;
  }

  private DonationStatRow statRow(Squadron unit, long donations, long donors) {
    return new DonationStatRow(unit.getId(), now.getYear(), now.getMonthValue(), donations, donors);
  }

  @Test
  void aMemberWithoutAnyManagedUnitSeesNoStatistics() {
    when(accessPolicy.isGoverningLeadership()).thenReturn(false);
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of());

    assertThatThrownBy(() -> service.stats(6)).isInstanceOf(AccessDeniedException.class);

    verify(donationRepository, never()).statsByUnitAndMonth(any(), any(), any());
  }

  @Test
  void aManagerSeesOnlyTheirOwnUnitsAndNoDonorList() {
    when(accessPolicy.isGoverningLeadership()).thenReturn(false);
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of(mine.getId()));
    when(orgUnitRepository.findAll()).thenReturn(List.of(mine, other));
    when(donationRepository.amountsByUnitMonthAndEntry(any(), any(), any()))
        .thenReturn(
            List.of(
                new DonationAmountRow(
                    mine.getId(), now.getYear(), now.getMonthValue(), "Quantainium", 7.5)));
    when(donationRepository.statsByUnitAndMonth(any(), any(), any()))
        .thenReturn(List.of(statRow(mine, 3, 2), statRow(other, 9, 9)));

    SupplyStatsDto stats = service.stats(3);

    assertThat(stats.months()).hasSize(3).last().isEqualTo(now.toString());
    assertThat(stats.units()).hasSize(1);
    assertThat(stats.units().getFirst().orgUnitName()).isEqualTo("Alpha");
    assertThat(stats.units().getFirst().donations()).isEqualTo(3);
    assertThat(stats.units().getFirst().distinctDonors()).isEqualTo(2);
    assertThat(stats.units().getFirst().amounts())
        .extracting(SupplyStatsDto.EntryAmount::entry)
        .containsExactly("Quantainium");
    assertThat(stats.donors()).isEmpty();
    verify(donationRepository, never()).amountsPerDonor(any());
  }

  @Test
  void theGoverningLeadershipSeesEveryUnitAndThePerDonorList() {
    User donor = new User();
    donor.setId(UUID.randomUUID());
    donor.setDisplayName("Zed");
    when(accessPolicy.isGoverningLeadership()).thenReturn(true);
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of());
    when(orgUnitRepository.findAll()).thenReturn(List.of(mine, other));
    when(donationRepository.amountsByUnitMonthAndEntry(any(), any(), any())).thenReturn(List.of());
    when(donationRepository.statsByUnitAndMonth(any(), any(), any()))
        .thenReturn(List.of(statRow(mine, 3, 2), statRow(other, 9, 9)));
    when(donationRepository.amountsPerDonor(any()))
        .thenReturn(List.of(new DonorAmountRow(donor.getId(), "Quantainium", 2L, 5.0)));
    when(userRepository.findAllById(Set.of(donor.getId()))).thenReturn(List.of(donor));

    SupplyStatsDto stats = service.stats(12);

    assertThat(stats.units()).hasSize(2);
    assertThat(stats.donors()).hasSize(1);
    assertThat(stats.donors().getFirst().entry()).isEqualTo("Quantainium");
    assertThat(stats.donors().getFirst().donations()).isEqualTo(2);
  }

  @Test
  void theSpanIsClampedToAtLeastOneAndAtMostThirtySixMonths() {
    when(accessPolicy.isGoverningLeadership()).thenReturn(true);
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of());
    when(orgUnitRepository.findAll()).thenReturn(List.of());
    when(donationRepository.amountsByUnitMonthAndEntry(any(), any(), any())).thenReturn(List.of());
    when(donationRepository.statsByUnitAndMonth(any(), any(), any())).thenReturn(List.of());
    when(donationRepository.amountsPerDonor(any())).thenReturn(List.of());

    assertThat(service.stats(0).months()).hasSize(1);
    assertThat(service.stats(500).months()).hasSize(36);
  }

  @Test
  void aMembersOwnTotalsSumTheirReceivedDonations() {
    UUID caller = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(caller));
    when(donationRepository.amountsByDonor(org.mockito.ArgumentMatchers.eq(caller), any()))
        .thenReturn(
            List.of(
                new DonorAmountRow(caller, "Quantainium", 2L, 5.0),
                new DonorAmountRow(caller, "Laranite", 1L, 1.0)));

    MyDonationStatsDto mineStats = service.mine();

    assertThat(mineStats.donations()).isEqualTo(3);
    assertThat(mineStats.entries()).hasSize(2);
  }

  @Test
  void ownTotalsNeedASignedInMember() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.mine()).isInstanceOf(AccessDeniedException.class);
  }
}
