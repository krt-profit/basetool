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

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import java.util.Map;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an admin sets a member rank outside the range the account's standing permits: officer
 * reach (a leadership seat held with {@code KRT Member}) holds ranks 1–12, {@code KRT Member}
 * without reach ranks 13–20 (REQ-ROLE-007).
 *
 * <p>Answers {@code 400} with code {@code RANK_OUTSIDE_ROLE_RANGE}. The message is the i18n key of
 * a basis-specific detail, so every client shows the reason and the way out in the caller's
 * language; the extension properties carry the basis and its permitted range.
 */
@Getter
public final class RankOutsideRoleRangeException extends DomainProblem {

  /** The basis whose range the rank left, a {@link RankRange.Basis} name. */
  @NotNull private final String role;

  /** The lowest rank the range permits. */
  private final int minRank;

  /** The highest rank the range permits. */
  private final int maxRank;

  /**
   * Creates the exception.
   *
   * @param detailKey the i18n key of the basis-specific detail
   * @param range the range the rank left
   */
  private RankOutsideRoleRangeException(@NotNull String detailKey, @NotNull RankRange range) {
    super(detailKey);
    this.role = range.basis().name();
    this.minRank = range.minRank();
    this.maxRank = range.maxRank();
  }

  /**
   * The refusal for a rank outside the given range.
   *
   * @param range the range the rank left; an admin's range cannot be left within 1–20
   * @return the exception naming the basis and its range
   */
  @NotNull
  public static RankOutsideRoleRangeException outside(@NotNull RankRange range) {
    String detailKey =
        switch (range.basis()) {
          case ADMIN -> "problem.rank_outside_role_range.detail";
          case LEADERSHIP_SEAT -> "problem.rank_outside_role_range.leadership_seat.detail";
          case KRT_MEMBER -> "problem.rank_outside_role_range.krt_member.detail";
        };
    return new RankOutsideRoleRangeException(detailKey, range);
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 400}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return IdentityProblemCode.RANK_OUTSIDE_ROLE_RANGE.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code RANK_OUTSIDE_ROLE_RANGE}
   */
  @NotNull
  @Override
  public String code() {
    return IdentityProblemCode.RANK_OUTSIDE_ROLE_RANGE.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Rank outside role range"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Rank outside role range";
  }

  /**
   * The basis and its permitted range, copied onto the problem response.
   *
   * @return {@code role} (the basis), {@code minRank} and {@code maxRank}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Map<String, Object> extraProperties() {
    return Map.of("role", role, "minRank", minRank, "maxRank", maxRank);
  }

  /**
   * The basis the handler's log line records.
   *
   * @return {@code role}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Map<String, ?> logExtra() {
    return Map.of("role", role);
  }
}
