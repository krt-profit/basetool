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
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * Refuses a write to stock rows or ships that a pending relocation locks: they were created before
 * the open home-location phase's live time and their holder has not chosen yet (REQ-PATCH-023,
 * ADR-0383).
 *
 * <p>Answered with {@code 409} and code {@link PatchChangeProblemCode#RELOCATION_PENDING}, on the
 * web and on the exchange alike.
 */
public final class RelocationPendingException extends DomainProblem {

  /** Creates the refusal; its detail resolves from {@code problem.relocation_pending.detail}. */
  public RelocationPendingException() {
    super("problem.relocation_pending.detail");
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 409}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return PatchChangeProblemCode.RELOCATION_PENDING.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code RELOCATION_PENDING}
   */
  @NotNull
  @Override
  public String code() {
    return PatchChangeProblemCode.RELOCATION_PENDING.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Relocation pending"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Relocation pending";
  }
}
