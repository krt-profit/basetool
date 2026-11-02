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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link NavigationFlag#holdsUserWide}: when a context-bound or data-tab right is lost
 * for good.
 */
class NavigationFlagTest {

  @Test
  @DisplayName("a supply tab is lost user-wide only while the supply surface is on")
  void aSupplyTabIsJudgedOnlyWhileTheSurfaceIsOn() {
    for (NavigationFlag tab : Set.of(NavigationFlag.SUPPLY_INCOMING, NavigationFlag.SUPPLY_STATS)) {
      assertThat(tab.holdsUserWide(Set.of())).as(tab.name()).isTrue();
      assertThat(tab.holdsUserWide(Set.of(NavigationFlag.PURCHASING))).as(tab.name()).isTrue();
      assertThat(tab.holdsUserWide(Set.of(NavigationFlag.SUPPLY))).as(tab.name()).isFalse();
      assertThat(tab.holdsUserWide(Set.of(NavigationFlag.SUPPLY, tab))).as(tab.name()).isTrue();
    }
  }

  @Test
  @DisplayName("a purchasing tab is lost user-wide only while the purchasing surface is on")
  void aPurchasingTabIsJudgedOnlyWhileTheSurfaceIsOn() {
    for (NavigationFlag tab :
        Set.of(
            NavigationFlag.PURCHASING_PARTNERS,
            NavigationFlag.PURCHASING_DELEGATES,
            NavigationFlag.PURCHASING_REPORT)) {
      assertThat(tab.holdsUserWide(Set.of())).as(tab.name()).isTrue();
      assertThat(tab.holdsUserWide(Set.of(NavigationFlag.SUPPLY))).as(tab.name()).isTrue();
      assertThat(tab.holdsUserWide(Set.of(NavigationFlag.PURCHASING))).as(tab.name()).isFalse();
      assertThat(tab.holdsUserWide(Set.of(NavigationFlag.PURCHASING, tab))).as(tab.name()).isTrue();
    }
  }

  @Test
  @DisplayName("the Leitung page still follows officer reach in any unit")
  void leitungStillFollowsOfficerReachAnywhere() {
    assertThat(NavigationFlag.OPEN_LEITUNG.holdsUserWide(Set.of())).isFalse();
    assertThat(NavigationFlag.OPEN_LEITUNG.holdsUserWide(Set.of(NavigationFlag.OFFICER_ANYWHERE)))
        .isTrue();
  }
}
