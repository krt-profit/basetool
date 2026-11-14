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

package de.greluc.krt.profit.basetool.backend.platform.api;

import de.greluc.krt.profit.basetool.backend.kernel.ProblemCode;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.security.core.Authentication;

/**
 * A step a member must take before the API serves them, such as accepting the Terms of Use
 * (REQ-SEC-028) or choosing a new home location (REQ-PATCH-020), ADR-0385.
 *
 * <p>The domain that owns the step implements it as a bean; {@link SessionGateAccessFilter} runs
 * every gate in its {@code @Order} and refuses with the first gate that holds the caller. A gate
 * never refuses its own endpoints nor those of a gate ordered before it, so each step stays
 * reachable while an earlier one holds the member.
 */
public interface SessionGate {

  /**
   * Returns the code a refusal carries; its title, detail and {@code type} follow from it.
   *
   * @return the gate's problem code, answered with 403
   */
  @NotNull
  ProblemCode problemCode();

  /**
   * Returns the endpoints the gate leaves open to a member it holds, as exact path patterns matched
   * on the decoded path.
   *
   * @return the gate's own exempt endpoints
   */
  @NotNull
  @Unmodifiable
  List<String> exemptPaths();

  /**
   * Tells whether the gate holds an authenticated caller.
   *
   * @param authentication the caller's authentication
   * @param userId the caller's user id
   * @return {@code true} when the caller must take the gate's step first
   */
  boolean holds(@NotNull Authentication authentication, @NotNull UUID userId);

  /**
   * Returns the detail of a refusal when the bundles carry none for the caller's locale.
   *
   * @return an English detail sentence
   */
  @NotNull
  String fallbackDetail();

  /**
   * Notes that the gate refused a caller, before the refusal is written.
   *
   * @param userId the refused caller
   */
  default void refused(@NotNull UUID userId) {}
}
