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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres tests of the full reset of the navigation preferences: every section of both modes,
 * both dashboards and both start pages go back to the default, every version moves on, and no other
 * member is touched (REQ-UI-032, REQ-UI-034).
 */
@SpringBootTest
@Transactional
class NavigationResetServiceTest {

  @Autowired private NavigationPreferencesService service;
  @Autowired private DashboardService dashboards;
  @Autowired private UserRepository userRepository;
  @Autowired private MeterRegistry meterRegistry;

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("reset-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  private void personalise(UUID owner) {
    service.replaceFavourites(owner, new FavouritesUpdateRequest(List.of("missions.list"), 0L));
    service.replaceHidden(owner, new HiddenUpdateRequest(List.of("orders.list"), 0L));
    service.replaceOrderScope(
        owner,
        "member",
        "groups",
        new OrderScopeUpdateRequest(List.of("logistics", "operations"), 0L));
    service.replaceOrderScope(
        owner, "admin", "groups", new OrderScopeUpdateRequest(List.of("admin-members"), 0L));
    service.replaceTabBar(
        owner,
        NavigationMode.MEMBER,
        new TabBarUpdateRequest(Arrays.asList("orders.list", null, null), 0L));
    service.replaceAppearance(owner, new AppearanceUpdateRequest(DesktopMode.PINNED, false, 0L));
    service.replaceStartPage(
        owner, NavigationMode.MEMBER, new StartPageUpdateRequest("missions.list", 1L));
    service.replaceStartPage(
        owner, NavigationMode.ADMIN, new StartPageUpdateRequest("admin.members", 2L));
    dashboards.replaceLayout(
        owner,
        DashboardMode.MEMBER,
        new DashboardLayoutRequest(
            List.of(
                new DashboardLayoutItem(
                    null, DashboardWidgetType.FAVOURITES, DashboardColumn.WIDE)),
            0L));
    dashboards.replaceLayout(
        owner,
        DashboardMode.ADMIN,
        new DashboardLayoutRequest(
            List.of(
                new DashboardLayoutItem(
                    null, DashboardWidgetType.ADMIN_HUB, DashboardColumn.NARROW)),
            0L));
  }

  private double ok(NavigationSection section) {
    return meterRegistry
        .counter(
            NavigationMetrics.PREFERENCE_WRITES,
            NavigationMetrics.TAG_SECTION,
            section.getTag(),
            MetricNames.TAG_OUTCOME,
            NavigationWriteOutcome.OK.getTag())
        .count();
  }

  @Test
  @DisplayName(
      "the reset clears every section of both modes and returns the desktop mode to the drawer")
  void clearsEverySection() {
    UUID owner = member();
    personalise(owner);

    NavigationPreferencesResponse reset = service.resetAll(owner);

    assertThat(reset.favourites().keys()).isEmpty();
    assertThat(reset.hidden().keys()).isEmpty();
    assertThat(reset.order().member().scopes()).isEmpty();
    assertThat(reset.order().admin().scopes()).isEmpty();
    assertThat(reset.tabBars().member().slots()).containsExactly(null, null, null);
    assertThat(reset.appearance().desktopMode()).isEqualTo(DesktopMode.DRAWER);
    assertThat(reset.appearance().shortcutsEnabled()).isTrue();
    assertThat(reset.appearance().startPageMember()).isNull();
    assertThat(reset.appearance().startPageAdmin()).isNull();
    assertThat(service.read(owner)).isEqualTo(reset);
  }

  @Test
  @DisplayName("the reset restores both dashboards to their default")
  void restoresBothDashboards() {
    UUID owner = member();
    personalise(owner);

    service.resetAll(owner);

    assertThat(dashboards.read(owner, DashboardMode.MEMBER).customised()).isFalse();
    assertThat(dashboards.read(owner, DashboardMode.MEMBER).widgets())
        .extracting(DashboardWidgetResponse::type)
        .contains(DashboardWidgetType.UPCOMING_DAYS);
    assertThat(dashboards.read(owner, DashboardMode.ADMIN).customised()).isFalse();
    assertThat(dashboards.read(owner, DashboardMode.ADMIN).widgets())
        .extracting(DashboardWidgetResponse::type)
        .contains(DashboardWidgetType.DELETION_REQUESTS);
  }

  @Test
  @DisplayName("every version moves on, so a stale tab conflicts instead of overwriting the reset")
  void bumpsEveryVersion() {
    UUID owner = member();
    personalise(owner);
    NavigationPreferencesResponse before = service.read(owner);
    long dashboardBefore = dashboards.read(owner, DashboardMode.MEMBER).version();

    NavigationPreferencesResponse reset = service.resetAll(owner);

    assertThat(reset.favourites().version()).isGreaterThan(before.favourites().version());
    assertThat(reset.hidden().version()).isGreaterThan(before.hidden().version());
    assertThat(reset.order().member().version()).isGreaterThan(before.order().member().version());
    assertThat(reset.order().admin().version()).isGreaterThan(before.order().admin().version());
    assertThat(reset.appearance().version()).isGreaterThan(before.appearance().version());
    assertThat(reset.tabBars().member().version())
        .isGreaterThan(before.tabBars().member().version());
    assertThat(reset.tabBars().admin().version()).isGreaterThan(before.tabBars().admin().version());
    assertThat(dashboards.read(owner, DashboardMode.MEMBER).version())
        .isGreaterThan(dashboardBefore);
    long stale = before.favourites().version();
    assertThatThrownBy(() ->
            service.replaceFavourites(
                owner, new FavouritesUpdateRequest(List.of("orders.list"), stale)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("a member who never chose anything can reset")
  void resetsAnEmptyAccount() {
    NavigationPreferencesResponse reset = service.resetAll(member());

    assertThat(reset.favourites().keys()).isEmpty();
    assertThat(reset.appearance().desktopMode()).isEqualTo(DesktopMode.DRAWER);
  }

  @Test
  @DisplayName("the reset counts one ok write per section")
  void countsEverySection() {
    UUID owner = member();
    double favourites = ok(NavigationSection.FAVOURITES);
    double dashboard = ok(NavigationSection.DASHBOARD);

    service.resetAll(owner);

    assertThat(ok(NavigationSection.FAVOURITES)).isEqualTo(favourites + 1);
    assertThat(ok(NavigationSection.DASHBOARD)).isEqualTo(dashboard + 1);
  }

  @Test
  @DisplayName("one member's reset never reaches another member")
  void keepsEachMembersPreferencesApart() {
    UUID owner = member();
    UUID other = member();
    personalise(owner);
    personalise(other);
    NavigationPreferencesResponse untouched = service.read(other);

    service.resetAll(owner);

    assertThat(service.read(other)).isEqualTo(untouched);
    assertThat(dashboards.read(other, DashboardMode.MEMBER).customised()).isTrue();
  }
}
