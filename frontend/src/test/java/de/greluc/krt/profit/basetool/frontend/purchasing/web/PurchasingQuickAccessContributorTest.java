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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessEntry;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PurchasingQuickAccessContributor}: the same tabs the purchasing pages show.
 */
@ExtendWith(MockitoExtension.class)
class PurchasingQuickAccessContributorTest {

  @Mock private PurchasingBackendClient purchasingClient;
  @Mock private PurchasingFeatureState featureState;

  @InjectMocks private PurchasingQuickAccessContributor contributor;

  /** While the purchasing surface is off, nothing is offered and the backend is not asked. */
  @Test
  void offersNothingWhileTheSurfaceIsOff() {
    when(featureState.enabled()).thenReturn(false);

    assertThat(contributor.entries()).isEmpty();
    verifyNoInteractions(purchasingClient);
  }

  /** A governing member who may read the report gets all three tabs. */
  @Test
  void governingMemberGetsEveryTab() {
    when(featureState.enabled()).thenReturn(true);
    when(purchasingClient.state())
        .thenReturn(new PurchasingStateDto(true, false, true, false, false, true, false));

    assertThat(contributor.entries())
        .extracting(QuickAccessEntry::href)
        .containsExactly("/purchasing/partners", "/purchasing/delegates", "/purchasing/report");
  }

  /** A delegate gets the partners only. */
  @Test
  void delegateGetsThePartners() {
    when(featureState.enabled()).thenReturn(true);
    when(purchasingClient.state())
        .thenReturn(new PurchasingStateDto(true, true, false, false, false, false, false));

    assertThat(contributor.entries())
        .extracting(QuickAccessEntry::href)
        .containsExactly("/purchasing/partners");
  }

  /** A state that reports the surface off, or no state at all, offers nothing. */
  @Test
  void disabledOrMissingStateOffersNothing() {
    when(featureState.enabled()).thenReturn(true);
    when(purchasingClient.state())
        .thenReturn(new PurchasingStateDto(false, true, true, false, false, true, false))
        .thenReturn(null);

    assertThat(contributor.entries()).isEmpty();
    assertThat(contributor.entries()).isEmpty();
  }

  /** A failed read fails closed. */
  @Test
  void failedReadOffersNothing() {
    when(featureState.enabled()).thenReturn(true);
    when(purchasingClient.state()).thenThrow(new IllegalStateException("backend down"));

    assertThat(contributor.entries()).isEmpty();
  }
}
