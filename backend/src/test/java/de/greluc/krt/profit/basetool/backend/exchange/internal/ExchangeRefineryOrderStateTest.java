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

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeRefineryOrderState.Good;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrigin;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeStoreLine;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The refinery order's state: what a dry run derives from an op, and how it survives the journal's
 * JSON column (REQ-XCH-052, REQ-XCH-056).
 */
class ExchangeRefineryOrderStateTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void aStateReadsBackAsItWasWritten() {
    ExchangeRefineryOrderState state =
        new ExchangeRefineryOrderState(
            UUID.randomUUID(),
            "ARC-L1",
            UUID.randomUUID(),
            Instant.parse("2030-01-01T10:30:00Z"),
            90L,
            RefineryOrderStatus.IN_PROGRESS,
            1250.5,
            null,
            400.0,
            List.of(
                new Good(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 500, 400, 700)),
            3L,
            1L,
            false,
            null);

    ExchangeRefineryOrderState read =
        ExchangeRefineryOrderState.read(
            mapper.readTree(mapper.writeValueAsString(state.toJournal())));

    assertThat(read).isEqualTo(state);
  }

  @Test
  void aDeletedOrdersStateKeepsItsMarkerUnitMissionAndPickup() {
    ExchangeRefineryOrderState state =
        new ExchangeRefineryOrderState(
                UUID.randomUUID(),
                "ARC-L1",
                null,
                null,
                null,
                RefineryOrderStatus.COMPLETED,
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

    ExchangeRefineryOrderState read =
        ExchangeRefineryOrderState.read(
            mapper.readTree(mapper.writeValueAsString(state.toJournal())));

    assertThat(read).isEqualTo(state);
  }

  @Test
  void aStateWithoutItsCountersReadsAsNone() {
    assertThat(
            ExchangeRefineryOrderState.read(
                mapper.readTree("{\"location\": \"" + UUID.randomUUID() + "\"}")))
        .isNull();
  }

  @Test
  void aPickupWithStoreLinesWritesTheSumOfTheLinesOnEachGoodBackIntoItsYield() {
    UUID refined = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    Good booked = new Good(UUID.randomUUID(), UUID.randomUUID(), refined, 6000, 450, 650);
    Good untouched = new Good(UUID.randomUUID(), UUID.randomUUID(), other, 100, 90, 300);
    ExchangeRefineryOrderState state =
        new ExchangeRefineryOrderState(
            UUID.randomUUID(),
            "ARC-L1",
            null,
            null,
            null,
            RefineryOrderStatus.IN_PROGRESS,
            null,
            null,
            null,
            List.of(booked, untouched),
            4L,
            1L,
            false,
            null);

    ExchangeRefineryOrderState picked =
        state.pickedUp(List.of(line(refined, 30.0), line(refined, 20.0)));

    assertThat(picked.status()).isEqualTo(RefineryOrderStatus.COMPLETED);
    assertThat(picked.stored()).isTrue();
    assertThat(picked.gameData()).isEqualTo(5L);
    assertThat(picked.goods()).containsExactly(booked.withOutputQuantity(5000), untouched);
  }

  @Test
  void aJournalGoodWrittenWithoutItsOutputReadsWithoutIt() {
    UUID id = UUID.randomUUID();
    UUID input = UUID.randomUUID();

    Good read =
        Good.read(
            mapper.readTree(
                "{\"id\": \"%s\", \"input\": \"%s\", \"inputQuantity\": 5, \"outputQuantity\": 4,"
                        .formatted(id, input)
                    + " \"quality\": 3}"));

    assertThat(read).isEqualTo(new Good(id, input, null, 5, 4, 3));
  }

  /**
   * A store line of an SCU material at grade 650 onto the member.
   *
   * @param material the material
   * @param amount the amount in SCU
   * @return the line
   */
  private static RefineryExchangeStoreLine line(UUID material, double amount) {
    return new RefineryExchangeStoreLine(
        material,
        UUID.randomUUID(),
        650,
        amount,
        QuantityType.SCU,
        UUID.randomUUID(),
        null,
        false,
        null,
        null);
  }
}
