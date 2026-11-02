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

package de.greluc.krt.profit.basetool.frontend.navigation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;

/** Unit tests of the promotion flags {@link NavigationAdvice#access} derives from capabilities. */
class NavigationAdvicePromotionFlagsTest {

  private NavigationAdvice advice;

  @BeforeEach
  void setUp() {
    advice =
        new NavigationAdvice(
            mock(NavigationRegistry.class),
            mock(RoleHierarchy.class),
            mock(NavigationPreferencesReader.class),
            mock(NavigationPreferencesLoader.class),
            mock(FrontendAuthHelperService.class),
            mock(NavigationTabBarResolver.class));
  }

  private NavigationAccess accessFor(CapabilitiesResponse capabilities) {
    return advice.access(null, Locale.GERMAN, capabilities, true, true, true);
  }

  @Test
  @DisplayName("the user-wide promotion flags follow their own capabilities, not officer reach")
  void userWidePromotionFlagsFollowTheirOwnCapabilities() {
    CapabilitiesResponse officerOfDisabledUnitsOnly =
        new CapabilitiesResponse(
            false, false, false, false, false, false, false, true, true, false, true, true, true,
            true, false, false);

    assertThat(accessFor(officerOfDisabledUnitsOnly).flags())
        .contains(NavigationFlag.OFFICER_ANYWHERE, NavigationFlag.OPEN_LEITUNG)
        .doesNotContain(
            NavigationFlag.MANAGE_PROMOTIONS,
            NavigationFlag.MANAGE_PROMOTIONS_ANYWHERE,
            NavigationFlag.PROMOTION_ANYWHERE);
  }

  @Test
  @DisplayName("a pin on a disabled unit keeps the user-wide flags, only the context flag drops")
  void aPinOnADisabledUnitKeepsTheUserWideFlags() {
    CapabilitiesResponse pinnedToDisabled =
        new CapabilitiesResponse(
            false, false, false, false, false, false, false, false, false, false, false, false,
            false, true, true, true);

    assertThat(accessFor(pinnedToDisabled).flags())
        .contains(NavigationFlag.MANAGE_PROMOTIONS_ANYWHERE, NavigationFlag.PROMOTION_ANYWHERE)
        .doesNotContain(NavigationFlag.MANAGE_PROMOTIONS);
  }

  @Test
  @DisplayName("an unresolved caller gets no promotion flag")
  void anUnresolvedCallerGetsNoPromotionFlag() {
    assertThat(accessFor(CapabilitiesResponse.NONE).flags())
        .doesNotContain(
            NavigationFlag.MANAGE_PROMOTIONS,
            NavigationFlag.MANAGE_PROMOTIONS_ANYWHERE,
            NavigationFlag.PROMOTION_ANYWHERE);
  }
}
