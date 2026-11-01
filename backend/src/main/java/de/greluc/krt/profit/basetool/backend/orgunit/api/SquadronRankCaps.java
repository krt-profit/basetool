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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The cardinality caps of the squadron ranks (REQ-ROLE-003, ADR-0322): &le;1 Staffelleiter per
 * squadron, &le;1 Kommandoleiter and &le;1 stellv. Kommandoleiter per group, &le;4 Kommandoleiter,
 * &le;4 stellv. and &le;4 Ensigns per squadron, with or without a group.
 *
 * <p>The three singleton caps are also backed by unique indexes; the per-squadron counts are
 * enforced only here.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SquadronRankCaps {

  /** Most Kommandoleiter, stellv. Kommandoleiter or Ensigns one Staffel may hold, per rank. */
  public static final int MAX_HOLDERS_PER_SQUADRON_RANK = 4;

  /**
   * Rejects a squadron-rank assignment that would breach a cap of the Staffel's current roster.
   *
   * @param roster the Staffel's memberships without the user being (re)assigned; never {@code
   *     null}.
   * @param rank the squadron rank being assigned; never {@code null}.
   * @param group the resolved Kommandogruppe, or {@code null}.
   * @throws BadRequestException when the assignment would breach a cap, or the rank is not a
   *     squadron rank.
   */
  public static void assertWithinCaps(
      @NotNull List<OrgUnitMembership> roster,
      @NotNull MembershipRole rank,
      @Nullable KommandoGroup group) {
    switch (rank) {
      case STAFFELLEITER -> {
        if (roster.stream().anyMatch(m -> m.getRole() == MembershipRole.STAFFELLEITER)) {
          throw new BadRequestException("This Staffel already has a Staffelleiter");
        }
      }
      case KOMMANDOLEITER, STELLV_KOMMANDOLEITER -> {
        if (group != null && holdsRankInGroup(roster, rank, group.getId())) {
          throw new BadRequestException("This Kommandogruppe already has a " + rank);
        }
        assertCountBelowMax(roster, rank);
      }
      case ENSIGN -> assertCountBelowMax(roster, rank);
      default -> throw new BadRequestException("Rank " + rank + " is not a squadron rank");
    }
  }

  private static boolean holdsRankInGroup(
      @NotNull List<OrgUnitMembership> roster,
      @NotNull MembershipRole rank,
      @NotNull UUID groupId) {
    return roster.stream()
        .anyMatch(m ->
            m.getRole() == rank
                && m.getKommandoGroup() != null
                && groupId.equals(m.getKommandoGroup().getId()));
  }

  private static void assertCountBelowMax(
      @NotNull List<OrgUnitMembership> roster, @NotNull MembershipRole rank) {
    long holders = roster.stream().filter(m -> m.getRole() == rank).count();
    if (holders >= MAX_HOLDERS_PER_SQUADRON_RANK) {
      throw new BadRequestException(
          "This Staffel already has the maximum of " + MAX_HOLDERS_PER_SQUADRON_RANK + " " + rank);
    }
  }
}
