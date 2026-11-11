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

package de.greluc.krt.profit.basetool.testsupport.process;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * A JVM that records its PID as {@code <dir>/pid-<depth>}, starts one child at {@code depth - 1}
 * while {@code depth > 0}, and then sleeps until it is killed.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ProcessTreeFixture {

  /**
   * Builds the command line that starts a fixture JVM.
   *
   * @param dir the directory the PID files go into
   * @param depth how many generations below this one to start
   * @return the argument vector
   * @throws URISyntaxException if the fixture's class location is not a valid URI
   */
  static @NotNull List<String> command(@NotNull Path dir, int depth) throws URISyntaxException {
    String java = ProcessHandle.current().info().command().orElseThrow();
    String classpath =
        Path.of(
                ProcessTreeFixture.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
            .toString();
    return List.of(
        java,
        "-cp",
        classpath,
        ProcessTreeFixture.class.getName(),
        dir.toString(),
        Integer.toString(depth));
  }

  /**
   * Records the PID, starts the next generation and sleeps.
   *
   * @param args the PID directory and the remaining depth
   * @throws IOException if the PID file cannot be written or the child cannot be started
   * @throws URISyntaxException if the fixture's class location is not a valid URI
   * @throws InterruptedException if the sleep is interrupted
   */
  public static void main(String[] args)
      throws IOException, URISyntaxException, InterruptedException {
    Path dir = Path.of(args[0]);
    int depth = Integer.parseInt(args[1]);
    if (depth > 0) {
      new ProcessBuilder(command(dir, depth - 1))
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
    }
    Path partial = dir.resolve("pid-" + depth + ".partial");
    Files.writeString(partial, Long.toString(ProcessHandle.current().pid()));
    Files.move(partial, dir.resolve("pid-" + depth), StandardCopyOption.ATOMIC_MOVE);
    Thread.sleep(Long.MAX_VALUE);
  }
}
