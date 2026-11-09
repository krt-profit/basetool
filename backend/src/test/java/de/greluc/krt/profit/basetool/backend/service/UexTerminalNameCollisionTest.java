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

package de.greluc.krt.profit.basetool.backend.service;

import static de.greluc.krt.profit.basetool.backend.service.UexFetchResults.fetched;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.UexClient;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.UexUniverseSyncService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexTerminalDto;
import de.greluc.krt.profit.basetool.backend.testcontext.LeafServiceMockTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies against the database that two UEX terminals sharing one name keep a row each, so a
 * commodity price on either resolves its terminal.
 *
 * <p>Writes are committed, so {@link #cleanUp()} removes the fixture.
 */
@LeafServiceMockTest
class UexTerminalNameCollisionTest {

  private static final String SHARED_NAME = "Platinum Bay - Collision Probe";
  private static final int ITEM_TERMINAL = 990_743;
  private static final int COMMODITY_TERMINAL = 990_744;

  @Autowired private UexUniverseSyncService service;
  @Autowired private TerminalRepository terminalRepository;
  @Autowired private UexClient uexClient;

  /** Deletes the committed terminals so the shared test database is left as it was found. */
  @AfterEach
  void cleanUp() {
    for (int uexId : List.of(ITEM_TERMINAL, COMMODITY_TERMINAL)) {
      terminalRepository.findByIdTerminal(uexId).ifPresent(terminalRepository::delete);
    }
  }

  @Test
  void terminalsSharingANameKeepOneStableRowEach() {
    Terminal manual = new Terminal();
    manual.setName(SHARED_NAME);
    UUID manualId = terminalRepository.save(manual).getId();
    when(uexClient.getTerminals())
        .thenReturn(
            fetched(
                List.of(
                    UexTerminalDto.builder().id(ITEM_TERMINAL).name(SHARED_NAME).type("item").build(),
                    UexTerminalDto.builder()
                        .id(COMMODITY_TERMINAL)
                        .name(SHARED_NAME)
                        .type("commodity")
                        .build())));

    service.syncTerminals();
    service.syncTerminals();

    Terminal item = terminalRepository.findByIdTerminal(ITEM_TERMINAL).orElseThrow();
    Terminal commodity = terminalRepository.findByIdTerminal(COMMODITY_TERMINAL).orElseThrow();
    assertEquals(manualId, item.getId(), "the unlinked row is adopted by the first terminal");
    assertNotEquals(item.getId(), commodity.getId(), "the second terminal gets a row of its own");
    assertEquals("item", item.getType());
    assertEquals("commodity", commodity.getType());
  }
}
