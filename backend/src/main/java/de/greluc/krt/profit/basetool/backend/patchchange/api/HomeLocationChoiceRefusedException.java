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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * Refuses a home-location choice (REQ-PATCH-021, REQ-PATCH-022), on the web and on the exchange
 * alike; nothing is stored or moved.
 *
 * <p>The code names the situation a client decides on; the detail resolves from the reason's own
 * message key.
 */
@Getter
public final class HomeLocationChoiceRefusedException extends DomainProblem {

  /** Why the choice was refused. */
  private final Reason reason;

  /**
   * Creates the refusal.
   *
   * @param reason why the choice was refused
   */
  public HomeLocationChoiceRefusedException(@NotNull Reason reason) {
    super(reason.messageKey);
    this.reason = reason;
  }

  /**
   * The status the refusal is answered with.
   *
   * @return the status of the reason's code
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return reason.code.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return the reason's code
   */
  @NotNull
  @Override
  public String code() {
    return reason.code.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Home-location choice refused"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Home-location choice refused";
  }

  /** Why a home-location choice was refused, with its code and message key. */
  @RequiredArgsConstructor
  public enum Reason {

    /** No home-location phase is open. */
    PHASE_NOT_OPEN(
        PatchChangeProblemCode.HOME_LOCATION_NOT_ASKED, "error.patch_change.phase_not_open"),

    /** The open phase does not ask the member: the account is younger than its live time. */
    NOT_ASKED(PatchChangeProblemCode.HOME_LOCATION_NOT_ASKED, "error.patch_change.not_asked"),

    /** The member has chosen in the open phase already. */
    ALREADY_CHOSEN(
        PatchChangeProblemCode.HOME_LOCATION_ALREADY_CHOSEN,
        "error.patch_change.already_relocated"),

    /** The location is no curated home location on offer. */
    NOT_OFFERED(
        PatchChangeProblemCode.HOME_LOCATION_NOT_OFFERED,
        "error.patch_change.home_location_not_offered");

    /** The problem code the refusal is answered with. */
    private final PatchChangeProblemCode code;

    /** The message key the detail resolves from. */
    private final String messageKey;
  }
}
