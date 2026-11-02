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
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres tests of the order section: the scopes, the version per mode, the independence from
 * the other sections, the reset, the deletion of entries and the write metric (REQ-UI-032).
 */
@SpringBootTest
@Transactional
class NavigationOrderTest {

  private static final String MEMBER = "member";
  private static final String ADMIN = "admin";
  private static final String GROUPS = "groups";

  @Autowired private NavigationPreferencesService service;
  @Autowired private UserRepository userRepository;
  @Autowired private MeterRegistry meterRegistry;

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("nav-order-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  private static OrderScopeUpdateRequest scope(long version, String... keys) {
    return new OrderScopeUpdateRequest(List.of(keys), version);
  }

  private double writes(NavigationWriteOutcome outcome) {
    return meterRegistry
        .counter(
            NavigationMetrics.PREFERENCE_WRITES,
            NavigationMetrics.TAG_SECTION,
            NavigationSection.ORDER.getTag(),
            MetricNames.TAG_OUTCOME,
            outcome.getTag())
        .count();
  }

  @Test
  @DisplayName("a member without a row reads an empty order at version 0 in both modes")
  void readsTheDefaults() {
    OrderResponse order = service.read(member()).order();

    assertThat(order.member().scopes()).isEmpty();
    assertThat(order.member().version()).isZero();
    assertThat(order.admin().scopes()).isEmpty();
    assertThat(order.admin().version()).isZero();
  }

  @Test
  @DisplayName("a scope write stores the keys in order and bumps only that mode's version")
  void storesTheScopeAndBumpsOnlyItsMode() {
    UUID owner = member();

    OrderModeResponse first =
        service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "logistics", "operations"));
    OrderModeResponse second =
        service.replaceOrderScope(
            owner, MEMBER, "operations", scope(1, "operations.operations", "operations.missions"));

    assertThat(first.scopes().get(GROUPS)).containsExactly("logistics", "operations");
    assertThat(first.version()).isEqualTo(1);
    assertThat(second.version()).isEqualTo(2);
    assertThat(second.scopes().keySet()).containsExactly(GROUPS, "operations");
    OrderResponse read = service.read(owner).order();
    assertThat(read.member()).isEqualTo(second);
    assertThat(read.admin().scopes()).isEmpty();
    assertThat(read.admin().version()).isZero();
  }

  @Test
  @DisplayName("rewriting a scope replaces its keys and leaves the other scopes alone")
  void rewritingAScopeReplacesOnlyIt() {
    UUID owner = member();
    service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "bank", "trade"));
    service.replaceOrderScope(owner, MEMBER, "trade", scope(1, "trade.a", "trade.b"));

    OrderModeResponse rewritten =
        service.replaceOrderScope(owner, MEMBER, GROUPS, scope(2, "trade", "bank"));

    assertThat(rewritten.scopes().get(GROUPS)).containsExactly("trade", "bank");
    assertThat(rewritten.scopes().get("trade")).containsExactly("trade.a", "trade.b");
  }

  @Test
  @DisplayName("an empty key list removes the scope")
  void anEmptyListRemovesTheScope() {
    UUID owner = member();
    service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "bank", "trade"));

    OrderModeResponse cleared = service.replaceOrderScope(owner, MEMBER, GROUPS, scope(1));

    assertThat(cleared.scopes()).isEmpty();
  }

  @Test
  @DisplayName("an order write never changes the favourites or hidden version, nor they its own")
  void sectionsDoNotCollide() {
    UUID owner = member();
    service.replaceFavourites(owner, new FavouritesUpdateRequest(List.of("missions.list"), 0L));

    service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "bank", "trade"));
    FavouritesResponse favourites =
        service.replaceFavourites(owner, new FavouritesUpdateRequest(List.of("orders.list"), 1L));

    assertThat(favourites.version()).isEqualTo(2);
    assertThat(service.read(owner).order().member().version()).isEqualTo(1);
  }

  @Test
  @DisplayName("the member and administration orders carry versions of their own")
  void eachModeHasItsOwnVersion() {
    UUID owner = member();
    service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "bank", "trade"));

    OrderModeResponse admin =
        service.replaceOrderScope(
            owner, ADMIN, GROUPS, scope(0, "admin-masterdata", "admin-members"));

    assertThat(admin.version()).isEqualTo(1);
    assertThat(service.read(owner).order().member().version()).isEqualTo(1);
  }

  @Test
  @DisplayName("a stale version is rejected, counted as a conflict and changes nothing")
  void rejectsAStaleVersion() {
    UUID owner = member();
    service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "bank", "trade"));
    double conflicts = writes(NavigationWriteOutcome.CONFLICT);

    assertThatThrownBy(
            () -> service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "trade", "bank")))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(writes(NavigationWriteOutcome.CONFLICT)).isEqualTo(conflicts + 1);
    assertThat(service.read(owner).order().member().scopes().get(GROUPS))
        .containsExactly("bank", "trade");
  }

  @Test
  @DisplayName("a repeated key, a malformed scope and an unknown mode are rejected and counted")
  void rejectsMalformedWrites() {
    UUID owner = member();
    double rejected = writes(NavigationWriteOutcome.REJECTED);

    assertThatThrownBy(
            () -> service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "bank", "bank")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () -> service.replaceOrderScope(owner, MEMBER, "Not A Key", scope(0, "bank")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.replaceOrderScope(owner, "other", GROUPS, scope(0, "bank")))
        .isInstanceOf(BadRequestException.class);

    assertThat(writes(NavigationWriteOutcome.REJECTED)).isEqualTo(rejected + 3);
  }

  @Test
  @DisplayName("a mode that would hold more scopes than the cap is rejected")
  void rejectsTooManyScopes() {
    UUID owner = member();
    IntStream.range(0, NavigationLimits.MAX_ORDER_SCOPES)
        .forEach(i -> service.replaceOrderScope(owner, MEMBER, "g" + i, scope(i, "k" + i)));

    assertThatThrownBy(() ->
            service.replaceOrderScope(
                owner, MEMBER, "overflow", scope(NavigationLimits.MAX_ORDER_SCOPES, "x")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("a reset clears the mode's order and bumps its version, the other mode stays")
  void resetClearsOneMode() {
    UUID owner = member();
    service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "bank", "trade"));
    service.replaceOrderScope(owner, ADMIN, GROUPS, scope(0, "admin-members", "admin-masterdata"));

    OrderModeResponse reset = service.resetOrder(owner, MEMBER, new OrderResetRequest(1L));

    assertThat(reset.scopes()).isEmpty();
    assertThat(reset.version()).isEqualTo(2);
    assertThat(service.read(owner).order().admin().scopes().get(GROUPS))
        .containsExactly("admin-members", "admin-masterdata");
  }

  @Test
  @DisplayName("a reset with a stale version is a conflict")
  void staleResetConflicts() {
    UUID owner = member();
    service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "bank", "trade"));

    assertThatThrownBy(() -> service.resetOrder(owner, MEMBER, new OrderResetRequest(0L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("pruning removes the named keys from the order of both modes and bumps the version")
  void pruneRemovesKeysFromTheOrder() {
    UUID owner = member();
    service.replaceOrderScope(owner, MEMBER, "trade", scope(0, "trade.a", "trade.gone", "trade.b"));

    OrderModeResponse member =
        service.prune(owner, List.of("trade.gone", "unknown.key")).order().member();

    assertThat(member.scopes().get("trade")).containsExactly("trade.a", "trade.b");
    assertThat(member.version()).isEqualTo(2);
  }

  @Test
  @DisplayName("one member's order never reaches another member")
  void keepsEachMembersOrderApart() {
    UUID owner = member();
    UUID other = member();
    service.replaceOrderScope(owner, MEMBER, GROUPS, scope(0, "bank", "trade"));

    OrderResponse otherRead = service.read(other).order();

    assertThat(otherRead.member().scopes()).isEmpty();
    assertThat(otherRead.member().version()).isZero();
  }
}
