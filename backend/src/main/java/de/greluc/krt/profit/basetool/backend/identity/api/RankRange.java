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

package de.greluc.krt.profit.basetool.backend.identity.api;

import org.jetbrains.annotations.NotNull;

/**
 * The member ranks an account may hold and what grants that range (REQ-ROLE-007).
 *
 * @param basis what the range follows from
 * @param minRank the lowest permitted rank
 * @param maxRank the highest permitted rank
 */
public record RankRange(@NotNull Basis basis, int minRank, int maxRank) {

  /** The lowest member rank any account may hold. */
  public static final int LOWEST_RANK = 1;

  /** The highest member rank any account may hold. */
  public static final int HIGHEST_RANK = 20;

  /** The highest rank of an account with officer reach. */
  public static final int HIGHEST_OFFICER_RANK = 12;

  /** What a {@link RankRange} follows from. */
  public enum Basis {
    /** The account holds {@code ADMIN}: any rank. */
    ADMIN,
    /** The account holds officer reach in at least one org unit. */
    LEADERSHIP_SEAT,
    /** The account holds {@code KRT Member} without officer reach. */
    KRT_MEMBER
  }

  /**
   * The range of an admin.
   *
   * @return ranks 1–20
   */
  @NotNull
  public static RankRange admin() {
    return new RankRange(Basis.ADMIN, LOWEST_RANK, HIGHEST_RANK);
  }

  /**
   * The range of an account with officer reach.
   *
   * @return ranks 1–12
   */
  @NotNull
  public static RankRange leadershipSeat() {
    return new RankRange(Basis.LEADERSHIP_SEAT, LOWEST_RANK, HIGHEST_OFFICER_RANK);
  }

  /**
   * The range of a {@code KRT Member} without officer reach.
   *
   * @return ranks 13–20
   */
  @NotNull
  public static RankRange krtMember() {
    return new RankRange(Basis.KRT_MEMBER, HIGHEST_OFFICER_RANK + 1, HIGHEST_RANK);
  }

  /**
   * Whether {@code rank} lies inside this range.
   *
   * @param rank the rank to test
   * @return {@code true} iff {@code minRank <= rank <= maxRank}
   */
  public boolean contains(int rank) {
    return rank >= minRank && rank <= maxRank;
  }
}
