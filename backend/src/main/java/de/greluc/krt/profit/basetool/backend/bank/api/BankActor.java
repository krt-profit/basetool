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

package de.greluc.krt.profit.basetool.backend.bank.api;

import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The member a bank access decision is made for, read from the database by {@link BankActors} and
 * never from a token (ADR-0352). Carries data only; every rule over it lives in {@link
 * BankAccessPolicy}.
 *
 * @param userId the member's id; {@code null} for {@link #NOBODY}
 * @param roles the role codes the member reaches through the role hierarchy, e.g. {@code
 *     BANK_EMPLOYEE} for a Bankleitung
 * @param seats the member's org-unit membership rows
 * @param officerReach the org units the member's seats reach as an officer, before the {@code KRT
 *     Member} gate (REQ-SEC-015)
 * @param grants the member's {@code bank_account_grant} rows by account id
 */
public record BankActor(
    @Nullable UUID userId,
    @NotNull @Unmodifiable Set<String> roles,
    @NotNull @Unmodifiable List<Seat> seats,
    @NotNull @Unmodifiable Set<UUID> officerReach,
    @NotNull @Unmodifiable Map<UUID, Grant> grants) {

  /** The actor of a request without a member: no roles, seats or grants. */
  public static final BankActor NOBODY =
      new BankActor(null, Set.of(), List.of(), Set.of(), Map.of());

  /**
   * Copies every collection, so the actor is immutable.
   *
   * @param userId the member's id; {@code null} for {@link #NOBODY}
   * @param roles the reachable role codes
   * @param seats the membership rows
   * @param officerReach the officer reach of the seats
   * @param grants the grant rows by account id
   */
  public BankActor {
    roles = Set.copyOf(roles);
    seats = List.copyOf(seats);
    officerReach = Set.copyOf(officerReach);
    grants = Map.copyOf(grants);
  }

  /**
   * Whether the member reaches the role.
   *
   * @param roleCode a bare role code, e.g. {@code Roles.BANK_MANAGEMENT}
   * @return {@code true} iff the code is among {@link #roles()}
   */
  public boolean holds(@NotNull String roleCode) {
    return roles.contains(roleCode);
  }

  /**
   * Whether the actor is the given member.
   *
   * @param otherUserId a member id, or {@code null}
   * @return {@code true} iff both ids are present and equal
   */
  public boolean is(@Nullable UUID otherUserId) {
    return userId != null && userId.equals(otherUserId);
  }

  /**
   * Whether the member holds the seat on any org unit.
   *
   * @param role the membership role
   * @return {@code true} iff a membership row carries the role
   */
  public boolean holdsSeat(@NotNull MembershipRole role) {
    return seats.stream().anyMatch(seat -> seat.role() == role);
  }

  /**
   * Whether the member holds exactly the seat on the org unit.
   *
   * @param orgUnitId the org unit
   * @param role the membership role
   * @return {@code true} iff a membership row on the unit carries the role
   */
  public boolean holdsSeatOn(@NotNull UUID orgUnitId, @NotNull MembershipRole role) {
    return seats.stream()
        .anyMatch(seat -> seat.orgUnitId().equals(orgUnitId) && seat.role() == role);
  }

  /**
   * Whether the member holds any seat on the org unit, a rank-less one included.
   *
   * @param orgUnitId the org unit
   * @return {@code true} iff a membership row is on the unit
   */
  public boolean isMemberOf(@NotNull UUID orgUnitId) {
    return seats.stream().anyMatch(seat -> seat.orgUnitId().equals(orgUnitId));
  }

  /**
   * The org units the member is a direct member of.
   *
   * @return the org-unit ids of the seats, in seat order
   */
  @NotNull
  @Unmodifiable
  public Set<UUID> memberOrgUnitIds() {
    Set<UUID> ids = new LinkedHashSet<>();
    seats.forEach(seat -> ids.add(seat.orgUnitId()));
    return Collections.unmodifiableSet(ids);
  }

  /**
   * One org-unit membership row.
   *
   * @param orgUnitId the org unit
   * @param role the seat held on it
   */
  public record Seat(@NotNull UUID orgUnitId, @NotNull MembershipRole role) {}

  /**
   * One {@code bank_account_grant} row of a bank employee (REQ-BANK-009); the row itself makes the
   * account visible.
   *
   * @param canDeposit whether deposits onto the account may be booked
   * @param canWithdraw whether withdrawals from the account may be booked
   * @param canTransfer whether transfers out of the account may be booked
   */
  public record Grant(boolean canDeposit, boolean canWithdraw, boolean canTransfer) {

    /**
     * Whether the grant carries the flag a booking or request of this kind needs.
     *
     * @param type the movement kind
     * @return the matching flag
     */
    public boolean allows(@NotNull BankBookingRequestType type) {
      return switch (type) {
        case DEPOSIT -> canDeposit;
        case WITHDRAWAL -> canWithdraw;
        case TRANSFER -> canTransfer;
      };
    }
  }
}
