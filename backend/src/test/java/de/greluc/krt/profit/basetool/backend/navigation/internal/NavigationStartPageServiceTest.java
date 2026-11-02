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
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
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
 * Real-Postgres tests of the start page per mode, which rides in the „Darstellung" section: the
 * default, the per-mode choice, the shared version, the 409, the key check and the deletion of a
 * key the member may no longer see (REQ-UI-034).
 */
@SpringBootTest
@Transactional
class NavigationStartPageServiceTest {

  private static final String MISSIONS = "missions.list";
  private static final String MEMBERS = "admin.members";

  @Autowired private NavigationPreferencesService service;
  @Autowired private UserRepository userRepository;
  @Autowired private MeterRegistry meterRegistry;

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("start-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  private double appearanceWrites(NavigationWriteOutcome outcome) {
    return meterRegistry
        .counter(
            NavigationMetrics.PREFERENCE_WRITES,
            NavigationMetrics.TAG_SECTION,
            NavigationSection.APPEARANCE.getTag(),
            MetricNames.TAG_OUTCOME,
            outcome.getTag())
        .count();
  }

  private static StartPageUpdateRequest page(String key, long version) {
    return new StartPageUpdateRequest(key, version);
  }

  @Test
  @DisplayName("a member who never chose has no start page in either mode")
  void defaultsToTheDashboard() {
    AppearanceResponse read = service.read(member()).appearance();

    assertThat(read.startPageMember()).isNull();
    assertThat(read.startPageAdmin()).isNull();
  }

  @Test
  @DisplayName("each mode keeps its own start page and every write bumps the shared version")
  void storesOnePagePerMode() {
    UUID owner = member();

    AppearanceResponse member =
        service.replaceStartPage(owner, NavigationMode.MEMBER, page(MISSIONS, 0));
    AppearanceResponse admin =
        service.replaceStartPage(owner, NavigationMode.ADMIN, page(MEMBERS, 1));

    assertThat(member.startPageMember()).isEqualTo(MISSIONS);
    assertThat(member.version()).isEqualTo(1);
    assertThat(admin.startPageMember()).isEqualTo(MISSIONS);
    assertThat(admin.startPageAdmin()).isEqualTo(MEMBERS);
    assertThat(admin.version()).isEqualTo(2);
    assertThat(service.read(owner).appearance()).isEqualTo(admin);
  }

  @Test
  @DisplayName("a null key returns the mode to the dashboard and leaves the other mode alone")
  void clearsOneMode() {
    UUID owner = member();
    service.replaceStartPage(owner, NavigationMode.MEMBER, page(MISSIONS, 0));
    service.replaceStartPage(owner, NavigationMode.ADMIN, page(MEMBERS, 1));

    AppearanceResponse cleared =
        service.replaceStartPage(owner, NavigationMode.MEMBER, page(null, 2));

    assertThat(cleared.startPageMember()).isNull();
    assertThat(cleared.startPageAdmin()).isEqualTo(MEMBERS);
  }

  @Test
  @DisplayName("a desktop-mode write keeps the start pages")
  void aModeWriteKeepsTheStartPages() {
    UUID owner = member();
    service.replaceStartPage(owner, NavigationMode.MEMBER, page(MISSIONS, 0));

    AppearanceResponse stored =
        service.replaceAppearance(owner, new AppearanceUpdateRequest(DesktopMode.RAIL, true, 1L));

    assertThat(stored.desktopMode()).isEqualTo(DesktopMode.RAIL);
    assertThat(stored.startPageMember()).isEqualTo(MISSIONS);
  }

  @Test
  @DisplayName("a start-page write never conflicts with the favourites version")
  void independentOfTheFavouritesVersion() {
    UUID owner = member();
    service.replaceFavourites(owner, new FavouritesUpdateRequest(List.of(MISSIONS), 0L));

    AppearanceResponse stored =
        service.replaceStartPage(owner, NavigationMode.MEMBER, page(MISSIONS, 0));

    assertThat(stored.version()).isEqualTo(1);
  }

  @Test
  @DisplayName("a stale version is rejected, counted as a conflict and changes nothing")
  void rejectsAStaleVersion() {
    UUID owner = member();
    service.replaceStartPage(owner, NavigationMode.MEMBER, page(MISSIONS, 0));
    double conflicts = appearanceWrites(NavigationWriteOutcome.CONFLICT);

    assertThatThrownBy(() -> service.replaceStartPage(owner, NavigationMode.MEMBER, page(null, 0)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(appearanceWrites(NavigationWriteOutcome.CONFLICT)).isEqualTo(conflicts + 1);
    assertThat(service.read(owner).appearance().startPageMember()).isEqualTo(MISSIONS);
  }

  @Test
  @DisplayName("a malformed key is rejected and counted")
  void rejectsAMalformedKey() {
    UUID owner = member();
    double rejected = appearanceWrites(NavigationWriteOutcome.REJECTED);

    assertThatThrownBy(
            () -> service.replaceStartPage(owner, NavigationMode.MEMBER, page("/missions?x=1", 0)))
        .isInstanceOf(BadRequestException.class);

    assertThat(appearanceWrites(NavigationWriteOutcome.REJECTED)).isEqualTo(rejected + 1);
    assertThat(service.read(owner).appearance().version()).isZero();
  }

  @Test
  @DisplayName("a committed write is counted as ok")
  void countsACommittedWrite() {
    double ok = appearanceWrites(NavigationWriteOutcome.OK);

    service.replaceStartPage(member(), NavigationMode.MEMBER, page(MISSIONS, 0));

    assertThat(appearanceWrites(NavigationWriteOutcome.OK)).isEqualTo(ok + 1);
  }

  @Test
  @DisplayName("deleting a key the member may no longer see clears a start page that names it")
  void pruneClearsAGoneStartPage() {
    UUID owner = member();
    service.replaceStartPage(owner, NavigationMode.MEMBER, page(MISSIONS, 0));
    service.replaceStartPage(owner, NavigationMode.ADMIN, page(MEMBERS, 1));

    AppearanceResponse stored = service.prune(owner, List.of(MEMBERS)).appearance();

    assertThat(stored.startPageAdmin()).isNull();
    assertThat(stored.startPageMember()).isEqualTo(MISSIONS);
    assertThat(stored.version()).isEqualTo(3);
  }

  @Test
  @DisplayName("deleting an unrelated key leaves the appearance version alone")
  void pruneOfAnotherKeyKeepsTheVersion() {
    UUID owner = member();
    service.replaceStartPage(owner, NavigationMode.MEMBER, page(MISSIONS, 0));

    AppearanceResponse stored = service.prune(owner, List.of("orders.demand")).appearance();

    assertThat(stored.version()).isEqualTo(1);
  }

  @Test
  @DisplayName("one member's start page never reaches another member")
  void keepsEachMembersPageApart() {
    UUID owner = member();
    UUID other = member();
    service.replaceStartPage(owner, NavigationMode.MEMBER, page(MISSIONS, 0));

    assertThat(service.read(other).appearance()).isEqualTo(AppearanceResponse.DEFAULT);
  }
}
