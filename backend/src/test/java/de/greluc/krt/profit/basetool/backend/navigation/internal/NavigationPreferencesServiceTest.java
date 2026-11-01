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
 * Real-Postgres tests of the favourites store: order, the per-section version, duplicate rejection,
 * owner isolation, deletion of entries and the write metric (REQ-UI-032).
 */
@SpringBootTest
@Transactional
class NavigationPreferencesServiceTest {

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

  private static FavouritesUpdateRequest favourites(long version, String... keys) {
    return new FavouritesUpdateRequest(List.of(keys), version);
  }

  private double writes(NavigationWriteOutcome outcome) {
    return meterRegistry
        .counter(
            NavigationMetrics.PREFERENCE_WRITES,
            NavigationMetrics.TAG_SECTION,
            NavigationSection.FAVOURITES.getTag(),
            MetricNames.TAG_OUTCOME,
            outcome.getTag())
        .count();
  }

  @Test
  @DisplayName("a member without a row reads empty favourites at version 0")
  void readsTheDefaultsOfAMemberWithoutARow() {
    FavouritesResponse read = service.read(member()).favourites();

    assertThat(read.keys()).isEmpty();
    assertThat(read.version()).isZero();
  }

  @Test
  @DisplayName("a write stores the keys in order and bumps the section version")
  void storesTheKeysInOrderAndBumpsTheVersion() {
    UUID owner = member();

    FavouritesResponse first =
        service.replaceFavourites(owner, favourites(0, "orders.demand", "missions.list"));
    FavouritesResponse second =
        service.replaceFavourites(
            owner, favourites(1, "missions.list", "orders.demand", "bank.accounts"));

    assertThat(first.keys()).containsExactly("orders.demand", "missions.list");
    assertThat(first.version()).isEqualTo(1);
    assertThat(second.keys()).containsExactly("missions.list", "orders.demand", "bank.accounts");
    assertThat(second.version()).isEqualTo(2);
    assertThat(service.read(owner).favourites()).isEqualTo(second);
  }

  @Test
  @DisplayName("a stale version is rejected, counted as a conflict and changes nothing")
  void rejectsAStaleVersion() {
    UUID owner = member();
    service.replaceFavourites(owner, favourites(0, "missions.list"));
    double conflicts = writes(NavigationWriteOutcome.CONFLICT);

    assertThatThrownBy(() -> service.replaceFavourites(owner, favourites(0, "orders.demand")))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(writes(NavigationWriteOutcome.CONFLICT)).isEqualTo(conflicts + 1);
    assertThat(service.read(owner).favourites().keys()).containsExactly("missions.list");
  }

  @Test
  @DisplayName("a repeated key is rejected and counted as rejected")
  void rejectsARepeatedKey() {
    UUID owner = member();
    double rejected = writes(NavigationWriteOutcome.REJECTED);

    assertThatThrownBy(
            () -> service.replaceFavourites(owner, favourites(0, "missions.list", "missions.list")))
        .isInstanceOf(BadRequestException.class);

    assertThat(writes(NavigationWriteOutcome.REJECTED)).isEqualTo(rejected + 1);
  }

  @Test
  @DisplayName("a committed write is counted as ok")
  void countsACommittedWrite() {
    UUID owner = member();
    double ok = writes(NavigationWriteOutcome.OK);

    service.replaceFavourites(owner, favourites(0, "missions.list"));

    assertThat(writes(NavigationWriteOutcome.OK)).isEqualTo(ok + 1);
  }

  @Test
  @DisplayName("one member's favourites and version never reach another member")
  void keepsEachMembersFavouritesApart() {
    UUID owner = member();
    UUID other = member();
    service.replaceFavourites(owner, favourites(0, "missions.list"));

    FavouritesResponse otherRead = service.read(other).favourites();

    assertThat(otherRead.keys()).isEmpty();
    assertThat(otherRead.version()).isZero();
  }

  @Test
  @DisplayName("pruning deletes the named keys, bumps the version and leaves the rest in order")
  void pruneDeletesTheNamedKeys() {
    UUID owner = member();
    service.replaceFavourites(
        owner, favourites(0, "missions.list", "orders.demand", "bank.accounts"));

    FavouritesResponse pruned =
        service.prune(owner, List.of("orders.demand", "unknown.key")).favourites();

    assertThat(pruned.keys()).containsExactly("missions.list", "bank.accounts");
    assertThat(pruned.version()).isEqualTo(2);
  }

  @Test
  @DisplayName("pruning keys that are not stored changes neither the keys nor the version")
  void pruneOfUnknownKeysIsANoOp() {
    UUID owner = member();
    service.replaceFavourites(owner, favourites(0, "missions.list"));

    FavouritesResponse pruned = service.prune(owner, List.of("orders.demand")).favourites();

    assertThat(pruned.keys()).containsExactly("missions.list");
    assertThat(pruned.version()).isEqualTo(1);
  }

  private static HiddenUpdateRequest hidden(long version, String... keys) {
    return new HiddenUpdateRequest(List.of(keys), version);
  }

  private static FavouritesAndHiddenUpdateRequest both(
      FavouritesUpdateRequest favourites, HiddenUpdateRequest hidden) {
    return new FavouritesAndHiddenUpdateRequest(favourites, hidden);
  }

  @Test
  @DisplayName("hidden pages are stored with their own version, apart from the favourites")
  void storesHiddenPagesWithTheirOwnVersion() {
    UUID owner = member();
    service.replaceFavourites(owner, favourites(0, "missions.list"));

    HiddenResponse stored =
        service.replaceHidden(owner, hidden(0, "bank.dashboard", "orders.list"));

    assertThat(stored.keys()).containsExactly("bank.dashboard", "orders.list");
    assertThat(stored.version()).isEqualTo(1);
    assertThat(service.read(owner).favourites().version()).isEqualTo(1);
  }

  @Test
  @DisplayName("a stale hidden version is rejected without touching the favourites version")
  void rejectsAStaleHiddenVersion() {
    UUID owner = member();
    service.replaceHidden(owner, hidden(0, "bank.dashboard"));

    assertThatThrownBy(() -> service.replaceHidden(owner, hidden(0, "orders.list")))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(service.read(owner).hidden().keys()).containsExactly("bank.dashboard");
    assertThat(service.read(owner).favourites().version()).isZero();
  }

  @Test
  @DisplayName("a favourite cannot be hidden and a hidden page cannot become a favourite")
  void refusesAPageThatWouldBeBoth() {
    UUID owner = member();
    service.replaceFavourites(owner, favourites(0, "missions.list"));
    service.replaceHidden(owner, hidden(0, "bank.dashboard"));

    assertThatThrownBy(() -> service.replaceHidden(owner, hidden(1, "missions.list")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() ->
            service.replaceFavourites(owner, favourites(1, "missions.list", "bank.dashboard")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() ->
            service.replaceFavouritesAndHidden(
                owner, both(favourites(1, "orders.list"), hidden(1, "orders.list"))))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("one request moves a page between the sections and bumps both versions")
  void movesAPageBetweenTheSectionsInOneRequest() {
    UUID owner = member();
    service.replaceFavourites(owner, favourites(0, "missions.list"));
    service.replaceHidden(owner, hidden(0, "bank.dashboard"));

    NavigationPreferencesResponse moved =
        service.replaceFavouritesAndHidden(
            owner, both(favourites(1, "missions.list", "bank.dashboard"), hidden(1)));

    assertThat(moved.favourites().keys()).containsExactly("missions.list", "bank.dashboard");
    assertThat(moved.favourites().version()).isEqualTo(2);
    assertThat(moved.hidden().keys()).isEmpty();
    assertThat(moved.hidden().version()).isEqualTo(2);
  }

  @Test
  @DisplayName("a stale version of either section drops the whole combined write")
  void aStaleVersionOfEitherSectionDropsTheCombinedWrite() {
    UUID owner = member();
    service.replaceFavourites(owner, favourites(0, "missions.list"));

    assertThatThrownBy(() ->
            service.replaceFavouritesAndHidden(
                owner, both(favourites(1, "orders.list"), hidden(5, "bank.dashboard"))))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThatThrownBy(() ->
            service.replaceFavouritesAndHidden(
                owner, both(favourites(0, "orders.list"), hidden(0, "bank.dashboard"))))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    NavigationPreferencesResponse read = service.read(owner);
    assertThat(read.favourites().keys()).containsExactly("missions.list");
    assertThat(read.favourites().version()).isEqualTo(1);
    assertThat(read.hidden().keys()).isEmpty();
    assertThat(read.hidden().version()).isZero();
  }

  @Test
  @DisplayName("hidden pages of one member never reach another member")
  void keepsEachMembersHiddenPagesApart() {
    UUID owner = member();
    service.replaceHidden(owner, hidden(0, "bank.dashboard"));

    assertThat(service.read(member()).hidden().keys()).isEmpty();
  }

  @Test
  @DisplayName("pruning deletes hidden keys too and bumps only the sections that changed")
  void pruneDeletesHiddenKeys() {
    UUID owner = member();
    service.replaceFavourites(owner, favourites(0, "missions.list"));
    service.replaceHidden(owner, hidden(0, "bank.dashboard", "orders.list"));

    NavigationPreferencesResponse pruned = service.prune(owner, List.of("orders.list"));

    assertThat(pruned.hidden().keys()).containsExactly("bank.dashboard");
    assertThat(pruned.hidden().version()).isEqualTo(2);
    assertThat(pruned.favourites().version()).isEqualTo(1);
  }

  @Test
  @DisplayName("pruning for a member without a row is a no-op")
  void pruneWithoutARowIsANoOp() {
    assertThat(service.prune(member(), List.of("orders.demand")).favourites().keys()).isEmpty();
  }
}
