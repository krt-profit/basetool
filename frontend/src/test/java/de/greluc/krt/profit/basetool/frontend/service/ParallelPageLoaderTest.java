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

package de.greluc.krt.profit.basetool.frontend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronContext;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpContext;
import de.greluc.krt.profit.basetool.frontend.logging.CorrelationContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParallelPageLoader}'s request-scoped context propagation onto the
 * virtual-thread workers.
 *
 * <p>The load-bearing case is {@link ClientIpContext} (#1130): the worker runs the WebClient
 * exchange and the {@code X-Forwarded-For} relay reads this thread-local at filter-assembly time,
 * so if the loader does not restore it the backend's per-IP rate limiter collapses every
 * parallelized read onto the one frontend-container IP — the regression this test guards against.
 * The sibling relay thread-locals ({@link ActiveSquadronContext}, {@link CorrelationContext}) are
 * asserted alongside so a future context added to the capture set cannot silently drop one.
 */
class ParallelPageLoaderTest {

  private final ParallelPageLoader loader = new ParallelPageLoader();

  @AfterEach
  void clearThreadLocals() {
    ClientIpContext.clear();
    ActiveSquadronContext.clear();
    CorrelationContext.clear();
  }

  @Test
  void loadAsyncPropagatesClientIpToTheWorkerThread() {
    // Given a client IP bound on the calling (request) thread
    ClientIpContext.set("203.0.113.7");

    // When the task runs on a virtual worker
    String seenOnWorker = loader.loadAsync(ClientIpContext::get).join();

    // Then the worker sees the same client IP (so the X-Forwarded-For relay fires)
    assertThat(seenOnWorker).isEqualTo("203.0.113.7");
  }

  @Test
  void loadAsyncPropagatesEveryRelayThreadLocalTogether() {
    // Given all three relay-driving thread-locals bound on the calling thread
    UUID squadron = UUID.randomUUID();
    ActiveSquadronContext.set(squadron);
    CorrelationContext.set("corr-123");
    ClientIpContext.set("198.51.100.9");

    // When the task runs on a virtual worker and reads all three
    String[] seen =
        loader
            .loadAsync(
                () ->
                    new String[] {
                      String.valueOf(ActiveSquadronContext.get()),
                      CorrelationContext.get(),
                      ClientIpContext.get()
                    })
            .join();

    // Then none was dropped on the hop to the worker
    assertThat(seen[0]).isEqualTo(squadron.toString());
    assertThat(seen[1]).isEqualTo("corr-123");
    assertThat(seen[2]).isEqualTo("198.51.100.9");
  }

  @Test
  void loadAsyncWorkerSeesNullWhenCallerHasNoClientIp() {
    // Given no client IP bound on the calling thread
    ClientIpContext.clear();

    // When the task runs on a virtual worker
    String seenOnWorker = loader.loadAsync(ClientIpContext::get).join();

    // Then the worker sees null (the null-guard never installs a stale value)
    assertThat(seenOnWorker).isNull();
  }

  @Test
  void loadAsyncLeavesTheCallingThreadClientIpUntouched() {
    // Given a client IP bound on the calling thread
    ClientIpContext.set("192.0.2.42");

    // When a task runs and completes on a worker
    loader.loadAsync(ClientIpContext::get).join();

    // Then the calling thread still holds its own value (the worker cleared only its own copy)
    assertThat(ClientIpContext.get()).isEqualTo("192.0.2.42");
  }
}
