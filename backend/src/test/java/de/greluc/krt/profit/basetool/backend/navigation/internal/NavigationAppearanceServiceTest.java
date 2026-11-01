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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres tests of the „Darstellung" section: the default, the own version counter, the 409,
 * independence from the favourites section, owner isolation and the write metric (REQ-UI-032).
 */
@SpringBootTest
@Transactional
class NavigationAppearanceServiceTest {

  @Autowired private NavigationPreferencesService service;
  @Autowired private UserRepository userRepository;
  @Autowired private MeterRegistry meterRegistry;

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("nav-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  private double writes(NavigationWriteOutcome outcome) {
    return meterRegistry
        .counter(
            NavigationMetrics.PREFERENCE_WRITES,
            NavigationMetrics.TAG_SECTION,
            NavigationSection.APPEARANCE.getTag(),
            MetricNames.TAG_OUTCOME,
            outcome.getTag())
        .count();
  }

  @Test
  @DisplayName("a member without a row reads the drawer at version 0")
  void readsTheDrawerByDefault() {
    AppearanceResponse read = service.read(member()).appearance();

    assertThat(read.desktopMode()).isEqualTo(DesktopMode.DRAWER);
    assertThat(read.shortcutsEnabled()).isTrue();
    assertThat(read.version()).isZero();
  }

  @Test
  @DisplayName("the shortcut switch is stored with the mode and survives a mode change")
  void storesTheShortcutSwitch() {
    UUID owner = member();

    AppearanceResponse off =
        service.replaceAppearance(
            owner, new AppearanceUpdateRequest(DesktopMode.DRAWER, false, 0L));
    AppearanceResponse rail =
        service.replaceAppearance(owner, new AppearanceUpdateRequest(DesktopMode.RAIL, false, 1L));

    assertThat(off).isEqualTo(new AppearanceResponse(DesktopMode.DRAWER, false, null, null, 1));
    assertThat(rail).isEqualTo(new AppearanceResponse(DesktopMode.RAIL, false, null, null, 2));
    assertThat(service.read(owner).appearance()).isEqualTo(rail);
  }

  @Test
  @DisplayName("a write stores the mode and bumps only the appearance version")
  void storesTheModeAndBumpsTheVersion() {
    UUID owner = member();

    AppearanceResponse first =
        service.replaceAppearance(owner, new AppearanceUpdateRequest(DesktopMode.RAIL, true, 0L));
    AppearanceResponse second =
        service.replaceAppearance(owner, new AppearanceUpdateRequest(DesktopMode.PINNED, true, 1L));

    assertThat(first).isEqualTo(new AppearanceResponse(DesktopMode.RAIL, true, null, null, 1));
    assertThat(second).isEqualTo(new AppearanceResponse(DesktopMode.PINNED, true, null, null, 2));
    assertThat(service.read(owner).appearance()).isEqualTo(second);
    assertThat(service.read(owner).favourites().version()).isZero();
  }

  @Test
  @DisplayName("an appearance write never conflicts with the favourites version")
  void independentOfTheFavouritesVersion() {
    UUID owner = member();
    service.replaceFavourites(owner, new FavouritesUpdateRequest(List.of("missions.list"), 0L));

    AppearanceResponse stored =
        service.replaceAppearance(owner, new AppearanceUpdateRequest(DesktopMode.RAIL, true, 0L));

    assertThat(stored.version()).isEqualTo(1);
    assertThat(service.read(owner).favourites().keys()).containsExactly("missions.list");
  }

  @Test
  @DisplayName("a stale version is rejected, counted as a conflict and changes nothing")
  void rejectsAStaleVersion() {
    UUID owner = member();
    service.replaceAppearance(owner, new AppearanceUpdateRequest(DesktopMode.RAIL, true, 0L));
    double conflicts = writes(NavigationWriteOutcome.CONFLICT);

    assertThatThrownBy(() ->
            service.replaceAppearance(
                owner, new AppearanceUpdateRequest(DesktopMode.PINNED, true, 0L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(writes(NavigationWriteOutcome.CONFLICT)).isEqualTo(conflicts + 1);
    assertThat(service.read(owner).appearance().desktopMode()).isEqualTo(DesktopMode.RAIL);
  }

  @Test
  @DisplayName("a committed write is counted as ok")
  void countsACommittedWrite() {
    double ok = writes(NavigationWriteOutcome.OK);

    service.replaceAppearance(member(), new AppearanceUpdateRequest(DesktopMode.RAIL, true, 0L));

    assertThat(writes(NavigationWriteOutcome.OK)).isEqualTo(ok + 1);
  }

  @Test
  @DisplayName("one member's mode never reaches another member")
  void keepsEachMembersModeApart() {
    UUID owner = member();
    UUID other = member();
    service.replaceAppearance(owner, new AppearanceUpdateRequest(DesktopMode.PINNED, true, 0L));

    assertThat(service.read(other).appearance()).isEqualTo(AppearanceResponse.DEFAULT);
  }
}
