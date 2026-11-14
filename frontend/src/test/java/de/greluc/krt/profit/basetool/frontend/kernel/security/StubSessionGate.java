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
import org.jetbrains.annotations.NotNull;

/** A session gate whose verdict a test sets, recording how often it was asked and forgotten. */
public final class StubSessionGate implements SessionGate {

  private final String pagePath;
  private final String ajaxHeader;
  private final String sseEvent;
  private final String problemCode;
  private boolean holding;
  private boolean knownHolding;
  private int asked;
  private int forgotten;

  /**
   * Creates a gate that holds nobody yet.
   *
   * @param pagePath its page
   * @param ajaxHeader its AJAX header
   * @param sseEvent its SSE event
   * @param problemCode the code of its backend refusal
   */
  public StubSessionGate(
      @NotNull String pagePath,
      @NotNull String ajaxHeader,
      @NotNull String sseEvent,
      @NotNull String problemCode) {
    this.pagePath = pagePath;
    this.ajaxHeader = ajaxHeader;
    this.sseEvent = sseEvent;
    this.problemCode = problemCode;
  }

  /**
   * Sets whether the gate holds every session it is asked about.
   *
   * @param holding the verdict
   * @return this gate
   */
  public @NotNull StubSessionGate holding(boolean holding) {
    this.holding = holding;
    return this;
  }

  /**
   * Sets whether the gate reports a fresh cached verdict that holds the session.
   *
   * @param knownHolding the cached verdict
   * @return this gate
   */
  public @NotNull StubSessionGate knownHolding(boolean knownHolding) {
    this.knownHolding = knownHolding;
    return this;
  }

  /**
   * Returns how often the gate was asked for a verdict.
   *
   * @return the number of {@link #holds} calls
   */
  public int asked() {
    return asked;
  }

  /**
   * Returns how often the gate's cached verdict was discarded.
   *
   * @return the number of {@link #forget} calls
   */
  public int forgotten() {
    return forgotten;
  }

  @Override
  public @NotNull String pagePath() {
    return pagePath;
  }

  @Override
  public @NotNull String ajaxHeader() {
    return ajaxHeader;
  }

  @Override
  public @NotNull String sseEvent() {
    return sseEvent;
  }

  @Override
  public @NotNull String problemCode() {
    return problemCode;
  }

  @Override
  public boolean holds(@NotNull HttpServletRequest request) {
    asked++;
    return holding;
  }

  @Override
  public boolean knownHolding(@NotNull HttpServletRequest request) {
    return knownHolding;
  }

  @Override
  public void forget(@NotNull HttpServletRequest request) {
    forgotten++;
  }
}
