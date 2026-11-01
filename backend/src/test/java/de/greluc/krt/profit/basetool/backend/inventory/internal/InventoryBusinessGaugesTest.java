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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelAllocation.Fill;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelStates.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The low-levels gauge counts low levels, not announced ones (REQ-OBS-011). */
@ExtendWith(MockitoExtension.class)
class InventoryBusinessGaugesTest {

  private static final String GAUGE = "basetool.inventory.minimum.levels.below";

  @Mock private StockLevelStates states;

  private static @NotNull State state(boolean low) {
    return new State(new StockLevel(), new Fill(low ? 1.0 : 9.0, 0.0, low ? 0.1 : 0.9, low));
  }

  @Test
  void theGaugeCountsExactlyTheLowLevelsOfTheLastSample() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    InventoryBusinessGauges gauges = new InventoryBusinessGauges(states);
    gauges.register(registry);
    when(states.ofActiveLevels())
        .thenReturn(List.of(state(true), state(false), state(true)))
        .thenReturn(List.of(state(false)));

    gauges.sample();
    assertThat(registry.get(GAUGE).gauge().value()).isEqualTo(2.0);

    gauges.sample();
    assertThat(registry.get(GAUGE).gauge().value()).isZero();
  }

  @Test
  void theGaugeCarriesNoLabel() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new InventoryBusinessGauges(states).register(registry);

    assertThat(registry.get(GAUGE).gauge().getId().getTags()).isEmpty();
  }
}
