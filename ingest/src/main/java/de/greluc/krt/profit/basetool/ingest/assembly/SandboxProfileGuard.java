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

package de.greluc.krt.profit.basetool.ingest.assembly;

import java.nio.file.Files;
import java.nio.file.Path;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a sandbox image under the {@code prod} profile (REQ-XCH-029): the public sandbox
 * images carry the marker file {@value #MARKER_PATH}, and none of them may ever run as production.
 * Runs after the profiles are resolved, so every way of activating {@code prod} is caught.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SandboxProfileGuard
    implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

  /** Where a sandbox image carries its marker. */
  static final String MARKER_PATH = "/app/SANDBOX";

  /** The marker file this guard looks for. */
  private final @NotNull Path marker;

  /** Creates the guard for the marker the sandbox images carry. */
  public SandboxProfileGuard() {
    this(Path.of(MARKER_PATH));
  }

  /**
   * Checks the prepared environment.
   *
   * @param event the event carrying the environment with its active profiles
   * @throws IllegalStateException when the marker exists and {@code prod} is active
   */
  @Override
  public void onApplicationEvent(@NotNull ApplicationEnvironmentPreparedEvent event) {
    check(event.getEnvironment());
  }

  /**
   * Refuses the {@code prod} profile in a sandbox image.
   *
   * @param environment the environment with its active profiles
   * @throws IllegalStateException when the marker exists and {@code prod} is active
   */
  void check(@NotNull Environment environment) {
    if (Files.exists(marker) && environment.matchesProfiles("prod")) {
      throw new IllegalStateException(
          "This is a sandbox image; it never runs with the prod profile (REQ-XCH-029).");
    }
  }

  /**
   * Runs last among the environment listeners, after the profiles are resolved.
   *
   * @return the lowest precedence
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
