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
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Names a refinery order's journal entries by the location its journaled state holds. */
class ExchangeEntryLabelsTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  private final ExchangeEntryLabels labels =
      new ExchangeEntryLabels(
          mock(MaterialRepository.class),
          mock(GameItemRepository.class),
          mock(ShipTypeRepository.class),
          mock(ShipRepository.class),
          mapper);

  @Test
  void aRefineryOrderIsNamedByItsLocationAfterTheWriteOrBeforeADelete() {
    String written = journaled("ARC-L1 Wide Forest Station");
    String deleted = journaled("CRU-L1 Ambitious Dream Station");
    ExchangeJournalEntry update = entry(journaled("HUR-L2 Faithful Dream"), written);
    ExchangeJournalEntry delete = entry(deleted, null);

    assertThat(labels.label(List.of(update, delete)))
        .containsEntry(update.getId(), "ARC-L1 Wide Forest Station")
        .containsEntry(delete.getId(), "CRU-L1 Ambitious Dream Station");
  }

  /**
   * A refinery order's journal state at a location.
   *
   * @param location the location's name
   * @return the state as the journal stores it
   */
  private String journaled(String location) {
    return mapper.writeValueAsString(
        new ExchangeRefineryOrderState(
                UUID.randomUUID(),
                location,
                null,
                null,
                null,
                RefineryOrderStatus.OPEN,
                null,
                null,
                null,
                List.of(),
                0L,
                0L,
                false,
                null)
            .toJournal());
  }

  /**
   * A journal entry of a refinery order.
   *
   * @param before the state before the write, or {@code null}
   * @param after the state after it, or {@code null} for a delete
   * @return the entry
   */
  private static ExchangeJournalEntry entry(String before, String after) {
    return ExchangeJournalEntry.builder()
        .id(UUID.randomUUID())
        .resource(ExchangeResource.REFINERY_ORDER)
        .entityKey(UUID.randomUUID().toString())
        .beforeState(before)
        .afterState(after)
        .build();
  }
}
