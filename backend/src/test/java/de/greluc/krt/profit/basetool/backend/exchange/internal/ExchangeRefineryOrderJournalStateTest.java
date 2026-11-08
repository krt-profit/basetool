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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderJournal.Good;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderJournal.State;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrigin;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The refinery order's journal state survives the journal's JSON column (REQ-XCH-056). */
class ExchangeRefineryOrderJournalStateTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void aStateReadsBackAsItWasWritten() {
    State state =
        new State(
            UUID.randomUUID(),
            "ARC-L1",
            UUID.randomUUID(),
            Instant.parse("2030-01-01T10:30:00Z"),
            90L,
            "IN_PROGRESS",
            1250.5,
            null,
            400.0,
            List.of(new Good(UUID.randomUUID(), UUID.randomUUID(), 500, 400, 700)),
            3L,
            1L,
            false,
            null);

    State read = State.read(mapper.readTree(mapper.writeValueAsString(state.toJournal())));

    assertThat(read).isEqualTo(state);
  }

  @Test
  void aDeletedOrdersStateKeepsItsMarkerUnitMissionAndPickup() {
    State state =
        new State(
                UUID.randomUUID(),
                "ARC-L1",
                null,
                null,
                null,
                "COMPLETED",
                null,
                null,
                null,
                List.of(),
                5L,
                2L,
                true,
                null)
            .deleted(
                new RefineryExchangeOrigin(
                    Instant.parse("2030-01-01T09:00:00Z"),
                    "vk-client",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    Instant.parse("2030-01-01T11:00:00Z"),
                    "vk-client",
                    null));

    State read = State.read(mapper.readTree(mapper.writeValueAsString(state.toJournal())));

    assertThat(read).isEqualTo(state);
  }

  @Test
  void aStateWithoutItsCountersReadsAsNone() {
    assertThat(State.read(mapper.readTree("{\"location\": \"" + UUID.randomUUID() + "\"}")))
        .isNull();
  }
}
