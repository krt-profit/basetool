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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Starts a real three-generation process tree and checks what {@link OwnedProcessTree} stops. */
class OwnedProcessTreeTest {

  /** Generations below the root: the tree is root, child and grandchild. */
  private static final int DEPTH = 2;

  /** Long enough for three JVMs to start one after another on a slow runner. */
  private static final Duration STARTUP = Duration.ofSeconds(60);

  /** The timeout of the timeout case; it must outlast {@link #DEPTH} + 1 JVM start-ups. */
  private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(15);

  /** A timeout the interrupt case never reaches. */
  private static final Duration LONG_TIMEOUT = Duration.ofMinutes(10);

  /** The stop budget handed to {@link OwnedProcessTree#run}. */
  private static final Duration STOP_BUDGET = Duration.ofSeconds(30);

  /** Poll interval while waiting for the fixture's PID files. */
  private static final Duration POLL = Duration.ofMillis(50);

  @TempDir Path dir;

  private final List<ProcessHandle> started = new ArrayList<>();

  @AfterEach
  void killLeftovers() {
    started.forEach(ProcessHandle::destroyForcibly);
  }

  @Test
  void timeoutStopsTheOwnedTreeBeforeItReturnsAndSparesAnUnrelatedProcess() throws Exception {
    ProcessHandle sentinel = startSentinel();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread runner = runInBackground(SHORT_TIMEOUT, failure);
    List<ProcessHandle> tree = awaitTree(dir);

    runner.join(SHORT_TIMEOUT.plus(STOP_BUDGET).plus(STARTUP));

    assertThat(runner.isAlive()).isFalse();
    assertThat(failure.get()).isInstanceOf(TimeoutException.class);
    assertThat(OwnedProcessTree.leftProcessesAlive(failure.get())).isFalse();
    assertThat(tree).noneMatch(ProcessHandle::isAlive);
    assertThat(sentinel.isAlive()).isTrue();
  }

  @Test
  void interruptStopsTheOwnedTreeAndRethrowsTheInterrupt() throws Exception {
    ProcessHandle sentinel = startSentinel();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread runner = runInBackground(LONG_TIMEOUT, failure);
    List<ProcessHandle> tree = awaitTree(dir);

    runner.interrupt();
    runner.join(STOP_BUDGET.plus(STARTUP));

    assertThat(runner.isAlive()).isFalse();
    assertThat(failure.get()).isInstanceOf(InterruptedException.class);
    assertThat(OwnedProcessTree.leftProcessesAlive(failure.get())).isFalse();
    assertThat(tree).noneMatch(ProcessHandle::isAlive);
    assertThat(sentinel.isAlive()).isTrue();
  }

  @Test
  void aProcessThatExitsOnItsOwnReturnsItsExitCode() throws Exception {
    ProcessBuilder exits =
        new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-version")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD);

    assertThat(OwnedProcessTree.run(exits, STARTUP, STOP_BUDGET)).isZero();
  }

  @Test
  void survivorsAreFoundThroughAWrappingFailure() {
    TimeoutException timedOut = new TimeoutException("timed out");
    timedOut.addSuppressed(new UnstoppedProcessesException(List.of(ProcessHandle.current())));

    assertThat(OwnedProcessTree.leftProcessesAlive(new IllegalStateException("up", timedOut)))
        .isTrue();
    assertThat(OwnedProcessTree.leftProcessesAlive(new IllegalStateException("up"))).isFalse();
  }

  /**
   * Starts a fixture JVM outside the owned tree and waits until it runs.
   *
   * @return the sentinel's handle
   * @throws Exception if it cannot be started or does not come up in time
   */
  private @NotNull ProcessHandle startSentinel() throws Exception {
    Path sentinelDir = Files.createDirectory(dir.resolve("sentinel"));
    new ProcessBuilder(ProcessTreeFixture.command(sentinelDir, 0))
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
    return awaitPid(sentinelDir, 0);
  }

  /**
   * Runs the three-generation fixture through {@link OwnedProcessTree#run} on its own thread.
   *
   * @param timeout the timeout handed to {@code run}
   * @param failure receives what {@code run} threw
   * @return the started thread
   */
  private @NotNull Thread runInBackground(
      @NotNull Duration timeout, @NotNull AtomicReference<Throwable> failure) {
    return Thread.ofPlatform()
        .start(() -> {
          try {
            OwnedProcessTree.run(
                new ProcessBuilder(ProcessTreeFixture.command(dir, DEPTH))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD),
                timeout,
                STOP_BUDGET);
          } catch (Exception thrown) {
            failure.set(thrown);
          }
        });
  }

  /**
   * Waits until every generation of the owned tree has recorded its PID.
   *
   * @param pidDir the directory the fixture writes into
   * @return the handles of root, child and grandchild, captured while they run
   * @throws Exception if a generation does not come up in time
   */
  private @NotNull List<ProcessHandle> awaitTree(@NotNull Path pidDir) throws Exception {
    List<ProcessHandle> tree = new ArrayList<>();
    for (int depth = DEPTH; depth >= 0; depth--) {
      tree.add(awaitPid(pidDir, depth));
    }
    return tree;
  }

  /**
   * Waits for {@code <pidDir>/pid-<depth>} and returns the live handle it names.
   *
   * @param pidDir the directory the fixture writes into
   * @param depth the generation to wait for
   * @return the handle, registered for cleanup after the test
   * @throws IOException if the PID file cannot be read
   * @throws InterruptedException if the wait is interrupted
   */
  private @NotNull ProcessHandle awaitPid(@NotNull Path pidDir, int depth)
      throws IOException, InterruptedException {
    Path pidFile = pidDir.resolve("pid-" + depth);
    long deadline = System.nanoTime() + STARTUP.toNanos();
    while (!Files.exists(pidFile)) {
      assertThat(System.nanoTime()).as("%s appears in time", pidFile).isLessThan(deadline);
      Thread.sleep(POLL);
    }
    ProcessHandle handle =
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow();
    started.add(handle);
    return handle;
  }
}
