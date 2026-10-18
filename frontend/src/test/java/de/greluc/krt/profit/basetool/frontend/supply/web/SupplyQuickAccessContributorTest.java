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

package de.greluc.krt.profit.basetool.frontend.supply.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessEntry;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link SupplyQuickAccessContributor}: the same tabs the supply pages show. */
@ExtendWith(MockitoExtension.class)
class SupplyQuickAccessContributorTest {

  @Mock private SupplyBackendClient supplyClient;
  @Mock private SupplyFeatureState featureState;
  @Mock private FrontendAuthHelperService authHelper;

  @InjectMocks private SupplyQuickAccessContributor contributor;

  /** While the supply surface is off, nothing is offered and the backend is not asked. */
  @Test
  void offersNothingWhileTheSurfaceIsOff() {
    when(featureState.enabled()).thenReturn(false);

    assertThat(contributor.entries()).isEmpty();
    verifyNoInteractions(supplyClient);
  }

  /** A manager of a receiving unit gets the incoming donations and the statistics. */
  @Test
  void managerOfAReceivingUnitGetsBothTabs() {
    when(featureState.enabled()).thenReturn(true);
    when(supplyClient.units()).thenReturn(List.of(unit(true, true)));

    assertThat(contributor.entries())
        .extracting(QuickAccessEntry::href)
        .containsExactly("/supply/donations/incoming", "/supply/stats");
  }

  /** An administrator who manages no unit gets the statistics only. */
  @Test
  void administratorGetsTheStatisticsOnly() {
    when(featureState.enabled()).thenReturn(true);
    when(supplyClient.units()).thenReturn(List.of(unit(false, true)));
    when(authHelper.isAdmin()).thenReturn(true);

    assertThat(contributor.entries())
        .extracting(QuickAccessEntry::href)
        .containsExactly("/supply/stats");
  }

  /** A plain member gets neither tab. */
  @Test
  void memberGetsNoTab() {
    when(featureState.enabled()).thenReturn(true);
    when(supplyClient.units()).thenReturn(null);

    assertThat(contributor.entries()).isEmpty();
  }

  /** A failed read fails closed. */
  @Test
  void failedReadOffersNothing() {
    when(featureState.enabled()).thenReturn(true);
    when(supplyClient.units()).thenThrow(new IllegalStateException("backend down"));

    assertThat(contributor.entries()).isEmpty();
  }

  private static @NotNull SupplyUnitDto unit(boolean canManage, boolean acceptsDonations) {
    return new SupplyUnitDto(
        UUID.randomUUID(), "Unit", "U", "SQUADRON", true, acceptsDonations, canManage, 0L);
  }
}
