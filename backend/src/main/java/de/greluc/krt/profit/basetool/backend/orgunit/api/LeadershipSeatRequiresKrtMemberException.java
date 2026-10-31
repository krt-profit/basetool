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

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a leadership seat is about to be given to a user without the {@code KRT Member} role
 * (REQ-SEC-015, ADR-0300).
 *
 * <p>Answers {@code 409} with code {@code LEADERSHIP_SEAT_REQUIRES_KRT_MEMBER}; nothing is written.
 */
public final class LeadershipSeatRequiresKrtMemberException extends DomainProblem {

  /** The bundle key the localised {@code detail} resolves from. */
  private static final String DETAIL_KEY = "problem.leadership_seat_requires_krt_member.detail";

  /** Creates the exception; its {@code detail} is the localised text of {@link #DETAIL_KEY}. */
  public LeadershipSeatRequiresKrtMemberException() {
    super(DETAIL_KEY);
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 409}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return OrgUnitProblemCode.LEADERSHIP_SEAT_REQUIRES_KRT_MEMBER.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code LEADERSHIP_SEAT_REQUIRES_KRT_MEMBER}
   */
  @NotNull
  @Override
  public String code() {
    return OrgUnitProblemCode.LEADERSHIP_SEAT_REQUIRES_KRT_MEMBER.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Leadership seat requires KRT Member"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Leadership seat requires KRT Member";
  }
}
