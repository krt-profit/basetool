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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Builds a {@link BankActor} for a unit test, as {@code DatabaseBankActors} would read it: roles
 * are the reachable codes, so a Bankleitung lists {@code BANK_EMPLOYEE} too (use {@link
 * #bankManagement()}).
 */
final class TestBankActor {

  private final UUID userId;
  private final Set<String> roles = new LinkedHashSet<>();
  private final List<BankActor.Seat> seats = new ArrayList<>();
  private final Set<UUID> officerReach = new LinkedHashSet<>();
  private final Map<UUID, BankActor.Grant> grants = new LinkedHashMap<>();

  private TestBankActor(@NotNull UUID userId) {
    this.userId = userId;
  }

  /**
   * Starts an actor without roles, seats or grants.
   *
   * @param userId the member's id
   * @return the builder
   */
  @NotNull
  static TestBankActor of(@NotNull UUID userId) {
    return new TestBankActor(userId);
  }

  /**
   * Starts a {@code KRT Member} actor.
   *
   * @param userId the member's id
   * @return the builder
   */
  @NotNull
  static TestBankActor member(@NotNull UUID userId) {
    return of(userId).roles(Roles.KRT_MEMBER);
  }

  /**
   * Adds reachable role codes.
   *
   * @param codes the codes
   * @return this builder
   */
  @NotNull
  TestBankActor roles(@NotNull String... codes) {
    roles.addAll(List.of(codes));
    return this;
  }

  /**
   * Adds the administrator's reachable roles: {@code ADMIN} and what the hierarchy implies.
   *
   * @return this builder
   */
  @NotNull
  TestBankActor admin() {
    return roles(
        Roles.ADMIN,
        Roles.LOGISTICIAN,
        Roles.MISSION_MANAGER,
        Roles.BANK_MANAGEMENT,
        Roles.BANK_EMPLOYEE);
  }

  /**
   * Adds the Bankleitung's reachable roles.
   *
   * @return this builder
   */
  @NotNull
  TestBankActor bankManagement() {
    return roles(Roles.BANK_MANAGEMENT, Roles.BANK_EMPLOYEE);
  }

  /**
   * Adds a seat; a seat that confers officer reach on its own unit adds that unit to the reach.
   *
   * @param orgUnitId the org unit
   * @param role the seat
   * @return this builder
   */
  @NotNull
  TestBankActor seat(@NotNull UUID orgUnitId, @NotNull MembershipRole role) {
    seats.add(new BankActor.Seat(orgUnitId, role));
    if (role.confersOwnUnitOfficerReach()) {
      officerReach.add(orgUnitId);
    }
    return this;
  }

  /**
   * Adds org units to the officer reach, as the leadership cascade would.
   *
   * @param orgUnitIds the reached units
   * @return this builder
   */
  @NotNull
  TestBankActor reach(@NotNull UUID... orgUnitIds) {
    officerReach.addAll(List.of(orgUnitIds));
    return this;
  }

  /**
   * Adds a bank grant row.
   *
   * @param accountId the account
   * @param deposit {@code can_deposit}
   * @param withdraw {@code can_withdraw}
   * @param transfer {@code can_transfer}
   * @return this builder
   */
  @NotNull
  TestBankActor grant(
      @NotNull UUID accountId, boolean deposit, boolean withdraw, boolean transfer) {
    grants.put(accountId, new BankActor.Grant(deposit, withdraw, transfer));
    return this;
  }

  /**
   * Builds the actor.
   *
   * @return the actor
   */
  @NotNull
  BankActor build() {
    return new BankActor(userId, roles, seats, officerReach, grants);
  }
}
