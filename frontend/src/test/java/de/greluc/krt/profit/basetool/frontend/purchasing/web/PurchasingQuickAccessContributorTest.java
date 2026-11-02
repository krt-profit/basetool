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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.web.NavigationRegistry;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PurchasingQuickAccessContributor}: the tabs the layout read's flags grant,
 * the same the purchasing pages show.
 */
@ExtendWith(MockitoExtension.class)
class PurchasingQuickAccessContributorTest {

  @Mock private PurchasingFeatureState featureState;
  @Mock private LayoutContextLoader layoutContextLoader;
  @Mock private HttpServletRequest request;

  private PurchasingQuickAccessContributor contributor;

  @BeforeEach
  void setUp() {
    contributor = new PurchasingQuickAccessContributor(featureState, layoutContextLoader, request);
  }

  private void grant(CapabilitiesResponse capabilities) {
    when(featureState.enabled()).thenReturn(true);
    when(layoutContextLoader.capabilities(request)).thenReturn(capabilities);
  }

  /** While the purchasing surface is off, nothing is offered and no flags are read. */
  @Test
  void offersNothingWhileTheSurfaceIsOff() {
    when(featureState.enabled()).thenReturn(false);

    assertThat(contributor.entries()).isEmpty();
    verifyNoInteractions(layoutContextLoader);
  }

  /** Every purchasing flag offers its tab. */
  @Test
  void everyFlagOffersItsTab() {
    grant(LayoutResponses.tabs(false, false, true, true, true));

    assertThat(contributor.entries())
        .extracting(QuickAccessEntry::href)
        .containsExactly("/purchasing/partners", "/purchasing/delegates", "/purchasing/report");
  }

  /** Every tab carries the key and the path of the registered tab, so it can be starred. */
  @Test
  void everyTabCarriesTheKeyOfItsRegisteredEntry() {
    grant(LayoutResponses.tabs(false, false, true, true, true));
    NavigationRegistry registry = new NavigationRegistry();

    assertThat(contributor.entries())
        .isNotEmpty()
        .allSatisfy(entry ->
            assertThat(registry.starrable(entry.entryKey()))
                .as(entry.href())
                .map(NavigationEntry::href)
                .contains(entry.href()));
  }

  /** The report flag alone offers the report only. */
  @Test
  void theReportFlagAloneOffersTheReport() {
    grant(LayoutResponses.tabs(true, true, false, false, true));

    assertThat(contributor.entries())
        .extracting(QuickAccessEntry::href)
        .containsExactly("/purchasing/report");
  }

  /** A plain member, or a failed layout read, gets no tab. */
  @Test
  void noFlagOffersNothing() {
    grant(CapabilitiesResponse.NONE);

    assertThat(contributor.entries()).isEmpty();
  }
}
