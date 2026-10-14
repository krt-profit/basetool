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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.inventory.api.DonationEarmark;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** What the Lager learns of a pledge from the donation earmark SPI (REQ-INV-058). */
@ExtendWith(MockitoExtension.class)
class SupplyDonationEarmarksTest {

  @Mock private SupplyDonationRepository donationRepository;
  @Mock private OrgUnitRepository orgUnitRepository;

  private SupplyDonationEarmarks earmarks;

  @BeforeEach
  void setUp() {
    earmarks = new SupplyDonationEarmarks(donationRepository, orgUnitRepository);
  }

  @Test
  void noIdsAreAnsweredWithoutAnyRead() {
    assertThat(earmarks.donations(Set.of())).isEmpty();

    verify(donationRepository, never()).findAllById(any());
  }

  @Test
  void aPledgeIsNamedByItsReceivingUnit() {
    Squadron unit = new Squadron();
    unit.setId(UUID.randomUUID());
    unit.setName("Bereich Profit");
    SupplyDonation donation = new SupplyDonation();
    donation.setId(UUID.randomUUID());
    donation.setReceivingOrgUnitId(unit.getId());
    when(donationRepository.findAllById(Set.of(donation.getId()))).thenReturn(List.of(donation));
    when(orgUnitRepository.findAllById(Set.of(unit.getId()))).thenReturn(List.of(unit));

    Map<UUID, DonationEarmark> found = earmarks.donations(Set.of(donation.getId()));

    assertThat(found).containsOnlyKeys(donation.getId());
    assertThat(found.get(donation.getId()).receivingUnitName()).isEqualTo("Bereich Profit");
  }

  @Test
  void anUnknownDonationIsAbsent() {
    UUID id = UUID.randomUUID();
    when(donationRepository.findAllById(Set.of(id))).thenReturn(List.of());
    when(orgUnitRepository.findAllById(Set.of())).thenReturn(List.of());

    assertThat(earmarks.donations(Set.of(id))).isEmpty();
  }
}
