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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The one list of the frontend's session gates, in their {@code @Order} (ADR-0385): the filter, the
 * stream and socket hand-offs, the error hand-off and the gate pages are all read from it.
 *
 * <p>A gate never holds a member on its own page nor on the page of a gate before it, so each step
 * stays reachable while an earlier one holds the member; the approval waiting page is open to every
 * gate.
 */
@Component
public class SessionGates {

  /** The gates in the order they run. */
  private final @Unmodifiable List<SessionGate> gates;

  /** Each gate's page, parsed once, in the gates' order. */
  private final @Unmodifiable List<PathPattern> pages;

  /**
   * Holds the gates the domains implement.
   *
   * @param gates the session gates, sorted by their {@code @Order}
   */
  public SessionGates(@NotNull List<SessionGate> gates) {
    this.gates = List.copyOf(gates);
    this.pages =
        this.gates.stream()
            .map(gate -> PathPatternParser.defaultInstance.parse(gate.pagePath()))
            .toList();
  }

  /**
   * Returns the gates in the order they run.
   *
   * @return the gates
   */
  public @NotNull @Unmodifiable List<SessionGate> all() {
    return gates;
  }

  /**
   * Finds the gate a backend refusal names.
   *
   * @param problemCode the {@code code} of the backend's 403
   * @return the gate whose refusal carries the code, or empty for any other refusal
   */
  public @NotNull Optional<SessionGate> refusedBy(@NotNull String problemCode) {
    return gates.stream().filter(gate -> gate.problemCode().equals(problemCode)).findFirst();
  }

  /**
   * Tells whether a path is a session gate's page or the approval waiting page with its status
   * poll. A gated session lands on these pages and they render without the active org unit.
   *
   * @param path a context-relative path, as {@link PublicPaths#relativePath} returns it
   * @return {@code true} for a gate's page, the waiting page or a path below the waiting page
   */
  public boolean isGatePage(@NotNull String path) {
    return isOpenTo(gates.size() - 1, PathContainer.parsePath(path));
  }

  /**
   * Tells whether a fresh cached verdict of any gate already holds the session, so a backend read
   * the gate's backend half would refuse can be skipped.
   *
   * @param request the current request
   * @return {@code true} when a gate knows it holds the session
   */
  public boolean anyKnownHolding(@NotNull HttpServletRequest request) {
    return gates.stream().anyMatch(gate -> gate.knownHolding(request));
  }

  /**
   * Tells whether the gate at an index leaves a path open: its own page, the page of every gate
   * before it, and the approval waiting page.
   *
   * @param index the gate's position in {@link #all()}
   * @param path the parsed context-relative path
   * @return {@code true} when the gate may not hold the member on the path
   */
  boolean isOpenTo(int index, @NotNull PathContainer path) {
    if (PublicPaths.isApprovalWaitingPage(path)) {
      return true;
    }
    for (int i = 0; i <= index && i < pages.size(); i++) {
      if (pages.get(i).matches(path)) {
        return true;
      }
    }
    return false;
  }
}
