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

package de.greluc.krt.profit.basetool.frontend.e2e;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;

/**
 * Starts the seeded E2E stack of this checkout for manual inspection and leaves it running, or
 * tears it down again (REQ-OPS-057, {@code docs/agents/ui-preview.md}).
 *
 * <p>Run through {@code :frontend:previewStackUp} and {@code :frontend:previewStackDown}; the
 * working directory must be inside the checkout whose stack is meant.
 */
public final class PreviewStack {

  /** What one invocation does. */
  enum Command {
    /** Builds, starts and seeds the stack, then leaves it running. */
    UP,
    /** Stops the stack and removes its volumes. */
    DOWN
  }

  private PreviewStack() {}

  /**
   * Runs the command named by the single argument, {@code up} or {@code down}.
   *
   * @param args exactly one of {@code up}, {@code down}
   * @throws Exception if the stack cannot be brought up or seeded; a failed bring-up is torn down
   */
  public static void main(String[] args) throws Exception {
    E2eStackExtension stack = new E2eStackExtension();
    switch (parse(args)) {
      case UP -> up(stack);
      case DOWN -> stack.tearDown();
    }
  }

  /**
   * Parses the command line.
   *
   * @param args the command-line arguments
   * @return the requested command
   * @throws IllegalArgumentException unless there is exactly one argument naming a command
   */
  static @NotNull Command parse(String @NotNull [] args) {
    if (args.length != 1) {
      throw new IllegalArgumentException("expected exactly one argument: up | down");
    }
    return Command.valueOf(args[0].trim().toUpperCase(Locale.ROOT));
  }

  /**
   * Brings the stack up and seeds it; tears it down again when any step after compose fails.
   *
   * @param stack the stack of this checkout
   * @throws Exception if bring-up or seeding fails
   */
  private static void up(@NotNull E2eStackExtension stack) throws Exception {
    AutoCloseable[] teardown = new AutoCloseable[1];
    try {
      stack.bringUpAndSeed(registered -> teardown[0] = registered);
    } catch (Exception failure) {
      if (teardown[0] != null) {
        teardown[0].close();
      }
      throw failure;
    }
    System.out.println(accessSummary(stack.baseUrl(), E2eUserRoles.ROLES));
  }

  /**
   * Describes how to reach the running stack: its URL, every account with its throwaway password
   * and roles, and the teardown task.
   *
   * @param baseUrl the frontend origin
   * @param roles the role codes by account
   * @return the multi-line summary printed after a successful bring-up
   */
  static @NotNull String accessSummary(
      @NotNull String baseUrl, @NotNull Map<String, ? extends Iterable<String>> roles) {
    StringBuilder out =
        new StringBuilder()
            .append("[PREVIEW] Stack is up and seeded: ")
            .append(baseUrl)
            .append(System.lineSeparator())
            .append("[PREVIEW] Throwaway accounts (username / password / roles):")
            .append(System.lineSeparator());
    new TreeMap<>(roles)
        .forEach((username, codes) ->
            out.append("[PREVIEW]   ")
                .append(username)
                .append(" / ")
                .append(E2eUserRoles.password(username))
                .append(" / ")
                .append(String.join(",", codes))
                .append(System.lineSeparator()));
    return out.append("[PREVIEW] Tear down with: ./gradlew :frontend:previewStackDown").toString();
  }
}
