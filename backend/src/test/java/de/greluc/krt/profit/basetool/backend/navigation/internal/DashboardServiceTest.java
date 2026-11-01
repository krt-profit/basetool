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
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres tests of the dashboard store: the member default, the 12-instance cap, the layout
 * and option versions, owner isolation, deletion of instances and the write metrics (REQ-UI-034).
 */
@SpringBootTest
@Transactional
class DashboardServiceTest {

  private static final DashboardMode MEMBER = DashboardMode.MEMBER;

  @Autowired private DashboardService service;
  @Autowired private UserRepository userRepository;
  @Autowired private MeterRegistry meterRegistry;

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("dash-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  private static DashboardLayoutItem item(DashboardWidgetType type) {
    return new DashboardLayoutItem(null, type, type.getDefaultColumn());
  }

  private static DashboardLayoutItem stored(DashboardWidgetResponse widget) {
    return new DashboardLayoutItem(widget.id(), widget.type(), widget.column());
  }

  private double writes(NavigationSection section, NavigationWriteOutcome outcome) {
    return meterRegistry
        .counter(
            NavigationMetrics.PREFERENCE_WRITES,
            NavigationMetrics.TAG_SECTION,
            section.getTag(),
            MetricNames.TAG_OUTCOME,
            outcome.getTag())
        .count();
  }

  @Test
  @DisplayName("a member who never changed the dashboard reads the member default at version 0")
  void readsTheMemberDefault() {
    DashboardResponse read = service.read(member(), MEMBER);

    assertThat(read.customised()).isFalse();
    assertThat(read.version()).isZero();
    assertThat(read.widgets())
        .extracting(DashboardWidgetResponse::type, DashboardWidgetResponse::column)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                DashboardWidgetType.UPCOMING_DAYS, DashboardColumn.WIDE),
            org.assertj.core.groups.Tuple.tuple(
                DashboardWidgetType.INFORMATION, DashboardColumn.WIDE),
            org.assertj.core.groups.Tuple.tuple(
                DashboardWidgetType.FAVOURITES, DashboardColumn.NARROW),
            org.assertj.core.groups.Tuple.tuple(
                DashboardWidgetType.NOTIFICATIONS, DashboardColumn.NARROW));
    assertThat(read.widgets()).allSatisfy(widget -> assertThat(widget.id()).isNull());
    assertThat(read.widgets().getFirst().days()).isEqualTo(7);
    assertThat(read.widgets().getFirst().rows()).isEqualTo(5);
  }

  @Test
  @DisplayName("the admin default is the hub in the wide column and four admin widgets")
  void readsTheAdminDefault() {
    DashboardResponse read = service.read(member(), DashboardMode.ADMIN);

    assertThat(read.customised()).isFalse();
    assertThat(read.widgets())
        .extracting(DashboardWidgetResponse::type, DashboardWidgetResponse::column)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                DashboardWidgetType.ADMIN_HUB, DashboardColumn.WIDE),
            org.assertj.core.groups.Tuple.tuple(
                DashboardWidgetType.DELETION_REQUESTS, DashboardColumn.NARROW),
            org.assertj.core.groups.Tuple.tuple(
                DashboardWidgetType.DISCORD_REGISTRATIONS, DashboardColumn.NARROW),
            org.assertj.core.groups.Tuple.tuple(
                DashboardWidgetType.SYNC_STATUS, DashboardColumn.NARROW),
            org.assertj.core.groups.Tuple.tuple(
                DashboardWidgetType.OPEN_TERMS, DashboardColumn.NARROW));
    assertThat(read.widgets().get(3).days()).isEqualTo(7);
    assertThat(read.widgets().get(1).rows()).isEqualTo(5);
  }

  @Test
  @DisplayName("an admin widget in the member dashboard is refused")
  void refusesAnAdminWidgetInTheMemberMode() {
    UUID owner = member();
    DashboardLayoutRequest request =
        new DashboardLayoutRequest(List.of(item(DashboardWidgetType.OPEN_TERMS)), 0L);

    assertThatThrownBy(() -> service.replaceLayout(owner, MEMBER, request))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("the sync status takes the ranges 1, 7 and 30 days and no other")
  void theSyncStatusTakesOnlyItsRanges() {
    UUID owner = member();
    DashboardWidgetResponse widget =
        service
            .replaceLayout(
                owner,
                DashboardMode.ADMIN,
                new DashboardLayoutRequest(List.of(item(DashboardWidgetType.SYNC_STATUS)), 0L))
            .widgets()
            .getFirst();

    DashboardWidgetResponse updated =
        service.replaceOptions(
            owner,
            DashboardMode.ADMIN,
            widget.id(),
            new DashboardOptionsRequest(null, 30, null, null, widget.optionsVersion()));

    assertThat(updated.days()).isEqualTo(30);
    assertThatThrownBy(() ->
            service.replaceOptions(
                owner,
                DashboardMode.ADMIN,
                widget.id(),
                new DashboardOptionsRequest(null, 14, null, null, updated.optionsVersion())))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("a layout write stores the instances in order, assigns ids and bumps the version")
  void storesTheLayoutAndBumpsTheVersion() {
    UUID owner = member();
    DashboardResponse base = service.read(owner, MEMBER);

    DashboardResponse written =
        service.replaceLayout(
            owner,
            MEMBER,
            new DashboardLayoutRequest(
                List.of(
                    item(DashboardWidgetType.NOTIFICATIONS),
                    item(DashboardWidgetType.UPCOMING_DAYS)),
                base.version()));

    assertThat(written.customised()).isTrue();
    assertThat(written.version()).isEqualTo(1);
    assertThat(written.widgets())
        .extracting(DashboardWidgetResponse::type)
        .containsExactly(DashboardWidgetType.NOTIFICATIONS, DashboardWidgetType.UPCOMING_DAYS);
    assertThat(written.widgets()).allSatisfy(widget -> assertThat(widget.id()).isNotNull());
    assertThat(service.read(owner, MEMBER)).isEqualTo(written);
  }

  @Test
  @DisplayName("an emptied dashboard stays empty and does not fall back to the default")
  void anEmptiedDashboardStaysEmpty() {
    UUID owner = member();

    service.replaceLayout(owner, MEMBER, new DashboardLayoutRequest(List.of(), 0L));

    DashboardResponse read = service.read(owner, MEMBER);
    assertThat(read.customised()).isTrue();
    assertThat(read.widgets()).isEmpty();
  }

  @Test
  @DisplayName("a stored instance keeps its id when it moves and a missing one is removed")
  void movesAndRemovesStoredInstances() {
    UUID owner = member();
    DashboardResponse first =
        service.replaceLayout(
            owner,
            MEMBER,
            new DashboardLayoutRequest(
                List.of(
                    item(DashboardWidgetType.UPCOMING_DAYS),
                    item(DashboardWidgetType.INFORMATION),
                    item(DashboardWidgetType.FAVOURITES)),
                0L));
    DashboardWidgetResponse upcoming = first.widgets().get(0);
    DashboardWidgetResponse favourites = first.widgets().get(2);

    DashboardResponse second =
        service.replaceLayout(
            owner,
            MEMBER,
            new DashboardLayoutRequest(
                List.of(
                    new DashboardLayoutItem(
                        favourites.id(), favourites.type(), DashboardColumn.WIDE),
                    stored(upcoming)),
                first.version()));

    assertThat(second.widgets())
        .extracting(DashboardWidgetResponse::id)
        .containsExactly(favourites.id(), upcoming.id());
    assertThat(second.widgets().getFirst().column()).isEqualTo(DashboardColumn.WIDE);
  }

  @Test
  @DisplayName("more than twelve instances are rejected and counted as rejected")
  void rejectsMoreThanTwelveInstances() {
    UUID owner = member();
    List<DashboardLayoutItem> items = new ArrayList<>();
    for (int i = 0; i < NavigationLimits.MAX_DASHBOARD_WIDGETS; i++) {
      items.add(item(DashboardWidgetType.UPCOMING_DAYS));
    }
    DashboardResponse full =
        service.replaceLayout(owner, MEMBER, new DashboardLayoutRequest(items, 0L));
    assertThat(full.widgets()).hasSize(12);
    List<DashboardLayoutItem> thirteen =
        new ArrayList<>(full.widgets().stream().map(DashboardServiceTest::stored).toList());
    thirteen.add(item(DashboardWidgetType.INFORMATION));

    assertThatThrownBy(() ->
            service.replaceLayout(
                owner, MEMBER, new DashboardLayoutRequest(thirteen, full.version())))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("an instance of another mode is rejected")
  void rejectsAWidgetOfAnotherMode() {
    UUID owner = member();
    assertThatThrownBy(() ->
            service.replaceLayout(
                owner,
                DashboardMode.ADMIN,
                new DashboardLayoutRequest(List.of(item(DashboardWidgetType.FAVOURITES)), 0L)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("an unknown instance id is rejected")
  void rejectsAnUnknownInstance() {
    UUID owner = member();
    assertThatThrownBy(() ->
            service.replaceLayout(
                owner,
                MEMBER,
                new DashboardLayoutRequest(
                    List.of(
                        new DashboardLayoutItem(
                            UUID.randomUUID(),
                            DashboardWidgetType.FAVOURITES,
                            DashboardColumn.NARROW)),
                    0L)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("a stale layout version is rejected, counted as a conflict and changes nothing")
  void rejectsAStaleLayoutVersion() {
    UUID owner = member();
    service.replaceLayout(
        owner,
        MEMBER,
        new DashboardLayoutRequest(List.of(item(DashboardWidgetType.FAVOURITES)), 0L));
    double conflicts = writes(NavigationSection.DASHBOARD, NavigationWriteOutcome.CONFLICT);

    assertThatThrownBy(
            () -> service.replaceLayout(owner, MEMBER, new DashboardLayoutRequest(List.of(), 0L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(writes(NavigationSection.DASHBOARD, NavigationWriteOutcome.CONFLICT))
        .isEqualTo(conflicts + 1);
    assertThat(service.read(owner, MEMBER).widgets()).hasSize(1);
  }

  @Test
  @DisplayName("an option write bumps only the instance version and does not touch the layout")
  void anOptionWriteLeavesTheLayoutVersionAlone() {
    UUID owner = member();
    DashboardResponse layout =
        service.replaceLayout(
            owner,
            MEMBER,
            new DashboardLayoutRequest(List.of(item(DashboardWidgetType.UPCOMING_DAYS)), 0L));
    DashboardWidgetResponse widget = layout.widgets().getFirst();

    DashboardWidgetResponse updated =
        service.replaceOptions(
            owner,
            MEMBER,
            widget.id(),
            new DashboardOptionsRequest(null, 14, 10, null, widget.optionsVersion()));

    assertThat(updated.days()).isEqualTo(14);
    assertThat(updated.rows()).isEqualTo(10);
    assertThat(updated.optionsVersion()).isEqualTo(1);
    assertThat(service.read(owner, MEMBER).version()).isEqualTo(layout.version());
  }

  @Test
  @DisplayName("a layout write after an option write needs no new layout version")
  void aLayoutWriteDoesNotConflictWithAnOptionWrite() {
    UUID owner = member();
    DashboardResponse layout =
        service.replaceLayout(
            owner,
            MEMBER,
            new DashboardLayoutRequest(
                List.of(
                    item(DashboardWidgetType.UPCOMING_DAYS), item(DashboardWidgetType.FAVOURITES)),
                0L));
    DashboardWidgetResponse widget = layout.widgets().getFirst();
    service.replaceOptions(
        owner,
        MEMBER,
        widget.id(),
        new DashboardOptionsRequest(null, 3, 3, null, widget.optionsVersion()));

    DashboardResponse reordered =
        service.replaceLayout(
            owner,
            MEMBER,
            new DashboardLayoutRequest(
                List.of(stored(layout.widgets().get(1)), stored(widget)), layout.version()));

    assertThat(reordered.widgets().get(1).days()).isEqualTo(3);
  }

  @Test
  @DisplayName("a stale option version is rejected")
  void rejectsAStaleOptionVersion() {
    UUID owner = member();
    DashboardWidgetResponse widget =
        service
            .replaceLayout(
                owner,
                MEMBER,
                new DashboardLayoutRequest(List.of(item(DashboardWidgetType.UPCOMING_DAYS)), 0L))
            .widgets()
            .getFirst();
    service.replaceOptions(
        owner, MEMBER, widget.id(), new DashboardOptionsRequest(null, 3, 5, null, 0L));

    assertThatThrownBy(() ->
            service.replaceOptions(
                owner, MEMBER, widget.id(), new DashboardOptionsRequest(null, 14, 5, null, 0L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("options the widget type does not offer or outside the allowed sets are rejected")
  void rejectsOptionsThatDoNotFit() {
    UUID owner = member();
    DashboardWidgetResponse widget =
        service
            .replaceLayout(
                owner,
                MEMBER,
                new DashboardLayoutRequest(List.of(item(DashboardWidgetType.NOTIFICATIONS)), 0L))
            .widgets()
            .getFirst();

    assertThatThrownBy(() ->
            service.replaceOptions(
                owner,
                MEMBER,
                widget.id(),
                new DashboardOptionsRequest(null, 7, 5, DashboardStatus.ALL, 0L)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() ->
            service.replaceOptions(
                owner,
                MEMBER,
                widget.id(),
                new DashboardOptionsRequest(null, null, 4, DashboardStatus.ALL, 0L)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("another member's instance is not found")
  void anotherMembersInstanceIsNotFound() {
    UUID owner = member();
    UUID other = member();
    DashboardWidgetResponse widget =
        service
            .replaceLayout(
                owner,
                MEMBER,
                new DashboardLayoutRequest(List.of(item(DashboardWidgetType.UPCOMING_DAYS)), 0L))
            .widgets()
            .getFirst();

    assertThatThrownBy(() ->
            service.replaceOptions(
                other, MEMBER, widget.id(), new DashboardOptionsRequest(null, 3, 5, null, 0L)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("restoring the default removes the instances and bumps the version")
  void resetRestoresTheDefault() {
    UUID owner = member();
    DashboardResponse layout =
        service.replaceLayout(
            owner,
            MEMBER,
            new DashboardLayoutRequest(List.of(item(DashboardWidgetType.FAVOURITES)), 0L));

    DashboardResponse reset = service.reset(owner, MEMBER, layout.version());

    assertThat(reset.customised()).isFalse();
    assertThat(reset.version()).isEqualTo(layout.version() + 1);
    assertThat(reset.widgets()).hasSize(4);
    assertThat(service.read(owner, MEMBER)).isEqualTo(reset);
  }

  @Test
  @DisplayName("pruning deletes the instances of the named types and bumps the layout version")
  void pruneDeletesInstancesOfLostWidgets() {
    UUID owner = member();
    DashboardResponse layout =
        service.replaceLayout(
            owner,
            MEMBER,
            new DashboardLayoutRequest(
                List.of(
                    item(DashboardWidgetType.UPCOMING_DAYS), item(DashboardWidgetType.FAVOURITES)),
                0L));

    DashboardResponse pruned = service.prune(owner, MEMBER, Set.of(DashboardWidgetType.FAVOURITES));

    assertThat(pruned.widgets())
        .extracting(DashboardWidgetResponse::type)
        .containsExactly(DashboardWidgetType.UPCOMING_DAYS);
    assertThat(pruned.version()).isEqualTo(layout.version() + 1);
  }

  @Test
  @DisplayName("pruning a type nobody holds changes nothing")
  void pruneWithoutAMatchChangesNothing() {
    UUID owner = member();
    DashboardResponse layout =
        service.replaceLayout(
            owner,
            MEMBER,
            new DashboardLayoutRequest(List.of(item(DashboardWidgetType.UPCOMING_DAYS)), 0L));

    assertThat(service.prune(owner, MEMBER, Set.of(DashboardWidgetType.NOTIFICATIONS)).version())
        .isEqualTo(layout.version());
  }

  @Test
  @DisplayName("the dashboards of two members are independent")
  void dashboardsAreOwnerIsolated() {
    UUID owner = member();
    UUID other = member();

    service.replaceLayout(owner, MEMBER, new DashboardLayoutRequest(List.of(), 0L));

    assertThat(service.read(other, MEMBER).customised()).isFalse();
  }

  @Test
  @DisplayName("committed layout and option writes are counted as ok")
  void countsCommittedWrites() {
    UUID owner = member();
    double ok = writes(NavigationSection.DASHBOARD, NavigationWriteOutcome.OK);

    DashboardWidgetResponse widget =
        service
            .replaceLayout(
                owner,
                MEMBER,
                new DashboardLayoutRequest(List.of(item(DashboardWidgetType.UPCOMING_DAYS)), 0L))
            .widgets()
            .getFirst();
    service.replaceOptions(
        owner, MEMBER, widget.id(), new DashboardOptionsRequest(null, 3, 5, null, 0L));

    assertThat(writes(NavigationSection.DASHBOARD, NavigationWriteOutcome.OK)).isEqualTo(ok + 2);
  }
}
