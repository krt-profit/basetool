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
 * Unit tests for {@link ParallelPageLoader}'s propagation of request-scoped context onto its
 * virtual-thread workers, chiefly {@link ClientIpContext} for the {@code X-Forwarded-For} relay,
 * plus {@link ActiveSquadronContext} and {@link CorrelationContext}.
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
    ClientIpContext.set("203.0.113.7");

    String seenOnWorker = loader.loadAsync(ClientIpContext::get).join();

    assertThat(seenOnWorker).isEqualTo("203.0.113.7");
  }

  @Test
  void loadAsyncPropagatesEveryRelayThreadLocalTogether() {
    UUID squadron = UUID.randomUUID();
    ActiveSquadronContext.set(squadron);
    CorrelationContext.set("corr-123");
    ClientIpContext.set("198.51.100.9");

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

    assertThat(seen[0]).isEqualTo(squadron.toString());
    assertThat(seen[1]).isEqualTo("corr-123");
    assertThat(seen[2]).isEqualTo("198.51.100.9");
  }

  @Test
  void loadAsyncWorkerSeesNullWhenCallerHasNoClientIp() {
    ClientIpContext.clear();

    String seenOnWorker = loader.loadAsync(ClientIpContext::get).join();

    assertThat(seenOnWorker).isNull();
  }

  @Test
  void loadAsyncLeavesTheCallingThreadClientIpUntouched() {
    ClientIpContext.set("192.0.2.42");

    loader.loadAsync(ClientIpContext::get).join();

    assertThat(ClientIpContext.get()).isEqualTo("192.0.2.42");
  }
}
