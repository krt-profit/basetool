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
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Runs a subprocess and, when it does not exit on its own, stops the process tree it started before
 * returning (REQ-OPS-056).
 *
 * <p>The owned tree is the root plus every descendant seen while one of its members was alive, so a
 * child that outlives its parent is still stopped. No other process is touched: a {@link
 * ProcessHandle} carries its start time, so a reused PID is never mistaken for an owned one.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OwnedProcessTree {

  /**
   * Starts {@code builder} and waits up to {@code timeout} for it to exit.
   *
   * <p>On a timeout or an interrupt the owned tree is stopped within {@code stopBudget} before the
   * failure is thrown; processes still alive after that are attached to the failure as a suppressed
   * {@link UnstoppedProcessesException}. The interrupt status is cleared while the tree is stopped
   * and the {@link InterruptedException} is rethrown afterwards.
   *
   * @param builder the configured process builder; its redirects stay as given
   * @param timeout how long the root may run before it counts as hung
   * @param stopBudget the upper bound for stopping the owned tree after a timeout or interrupt
   * @return the exit code of the root process
   * @throws IOException if the process cannot be started
   * @throws InterruptedException if the waiting thread is interrupted
   * @throws TimeoutException if the root does not exit within {@code timeout}
   */
  public static int run(
      @NotNull ProcessBuilder builder, @NotNull Duration timeout, @NotNull Duration stopBudget)
      throws IOException, InterruptedException, TimeoutException {
    Process process = builder.start();
    try {
      if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        return process.exitValue();
      }
    } catch (InterruptedException interrupted) {
      throw stopAfter(process.toHandle(), stopBudget, interrupted);
    }
    throw stopAfter(
        process.toHandle(),
        stopBudget,
        new TimeoutException("process " + process.pid() + " did not exit within " + timeout));
  }

  /**
   * Reports whether a failure, or any failure in its cause chain, left owned processes alive.
   *
   * @param failure the failure thrown by {@link #run} or a wrapper around it
   * @return {@code true} when an {@link UnstoppedProcessesException} is attached as suppressed
   */
  public static boolean leftProcessesAlive(@NotNull Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      for (Throwable suppressed : current.getSuppressed()) {
        if (suppressed instanceof UnstoppedProcessesException) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Stops the tree rooted at {@code root} and attaches the survivors, if any, to {@code failure}.
   *
   * @param root the root process handle
   * @param budget the upper bound for stopping the tree
   * @param failure the failure that triggered the stop
   * @param <T> the failure type
   * @return {@code failure}, for the caller to throw
   */
  private static <T extends Exception> @NotNull T stopAfter(
      @NotNull ProcessHandle root, @NotNull Duration budget, @NotNull T failure) {
    List<ProcessHandle> survivors = stop(root, budget);
    if (!survivors.isEmpty()) {
      failure.addSuppressed(new UnstoppedProcessesException(survivors));
    }
    return failure;
  }

  /**
   * Forcibly stops the root and every descendant, re-reading the descendants of each live member
   * every round, until all are gone or {@code budget} runs out.
   *
   * @param root the root process handle
   * @param budget the upper bound for the whole stop
   * @return the owned processes still alive when the budget ran out, empty when all are gone
   */
  private static @Unmodifiable @NotNull List<ProcessHandle> stop(
      @NotNull ProcessHandle root, @NotNull Duration budget) {
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + budget.toNanos();
    SequencedSet<ProcessHandle> owned = new LinkedHashSet<>();
    owned.add(root);
    List<ProcessHandle> alive;
    try {
      do {
        collectDescendants(owned);
        owned.reversed().forEach(ProcessHandle::destroyForcibly);
        interrupted |= awaitExit(owned, deadline);
        alive = owned.stream().filter(ProcessHandle::isAlive).toList();
      } while (!alive.isEmpty() && System.nanoTime() < deadline);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    return alive;
  }

  /**
   * Adds the current descendants of every live member to {@code owned}; members already dead keep
   * their place, so an orphan seen earlier is not forgotten.
   *
   * @param owned the owned set, root first, in discovery order
   */
  private static void collectDescendants(@NotNull Set<ProcessHandle> owned) {
    for (ProcessHandle member : List.copyOf(owned)) {
      if (member.isAlive()) {
        member.descendants().forEach(owned::add);
      }
    }
  }

  /**
   * Waits for every owned process to exit, but not past {@code deadline}.
   *
   * @param owned the owned processes
   * @param deadline the {@link System#nanoTime()} value at which waiting stops
   * @return {@code true} if the thread was interrupted while waiting
   */
  private static boolean awaitExit(@NotNull Set<ProcessHandle> owned, long deadline) {
    boolean interrupted = false;
    for (ProcessHandle member : owned) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        break;
      }
      try {
        member.onExit().get(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException _) {
        interrupted = true;
      } catch (ExecutionException | TimeoutException _) {
        break;
      }
    }
    return interrupted;
  }
}
