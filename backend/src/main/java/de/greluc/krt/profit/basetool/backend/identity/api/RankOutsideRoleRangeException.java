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
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.util.Map;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an admin sets a member rank that the member's stored Keycloak role does not permit:
 * {@code OFFICER} holds ranks 1–12, {@code KRT_MEMBER} ranks 13–20 (REQ-ROLE-007).
 *
 * <p>Answers {@code 400} with code {@code RANK_OUTSIDE_ROLE_RANGE}. The message is the i18n key of
 * a role-specific detail, so every client shows the reason and the way out in the caller's
 * language; the extension properties carry the role and its permitted range.
 */
@Getter
public final class RankOutsideRoleRangeException extends DomainProblem {

  /** The stored role whose range the rank left, {@code OFFICER} or {@code KRT_MEMBER}. */
  @NotNull private final String role;

  /** The lowest rank the role permits. */
  private final int minRank;

  /** The highest rank the role permits. */
  private final int maxRank;

  /**
   * Creates the exception.
   *
   * @param detailKey the i18n key of the role-specific detail
   * @param role the stored role whose range the rank left
   * @param minRank the lowest rank the role permits
   * @param maxRank the highest rank the role permits
   */
  private RankOutsideRoleRangeException(
      @NotNull String detailKey, @NotNull String role, int minRank, int maxRank) {
    super(detailKey);
    this.role = role;
    this.minRank = minRank;
    this.maxRank = maxRank;
  }

  /**
   * The refusal for a member who still holds the {@code OFFICER} role.
   *
   * @return the exception naming the officer range 1–12
   */
  @NotNull
  public static RankOutsideRoleRangeException officer() {
    return new RankOutsideRoleRangeException(
        "problem.rank_outside_role_range.officer.detail", Roles.OFFICER, 1, 12);
  }

  /**
   * The refusal for a member who holds {@code KRT_MEMBER} without {@code OFFICER}.
   *
   * @return the exception naming the member range 13–20
   */
  @NotNull
  public static RankOutsideRoleRangeException member() {
    return new RankOutsideRoleRangeException(
        "problem.rank_outside_role_range.member.detail", Roles.KRT_MEMBER, 13, 20);
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
   * The role and its permitted range, copied onto the problem response.
   *
   * @return {@code role}, {@code minRank} and {@code maxRank}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Map<String, Object> extraProperties() {
    return Map.of("role", role, "minRank", minRank, "maxRank", maxRank);
  }

  /**
   * The role the handler's log line records.
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
