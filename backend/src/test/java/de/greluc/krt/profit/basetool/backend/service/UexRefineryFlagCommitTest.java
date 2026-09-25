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

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies that {@code UexUniverseSyncService.reconcileRefineryTerminalFlags()} commits its flags
 * when called without an ambient transaction (REQ-REFINERY-020); unlike {@link
 * UexUniverseSyncRefineryFlagTest} it is deliberately not transactional.
 *
 * <p>Writes are committed, so {@link #cleanUp()} removes the fixture.
 */
@SpringBootTest
@ActiveProfiles("test")
class UexRefineryFlagCommitTest {

  private static final String STATION_NAME = "Commit-Probe Refinery Station";

  @MockitoBean private UexClient uexClient;

  @Autowired private UexUniverseSyncService service;

  @Autowired private SpaceStationRepository spaceStationRepository;

  @Autowired private TerminalRepository terminalRepository;

  private UUID stationId;
  private UUID terminalId;

  /** Deletes the committed fixture rows so the shared test database is left as it was found. */
  @AfterEach
  void cleanUp() {
    if (terminalId != null) {
      terminalRepository.deleteById(terminalId);
    }
    if (stationId != null) {
      spaceStationRepository.deleteById(stationId);
    }
  }

  /**
   * Reconciling outside any ambient transaction must persist the derived flag, proving the
   * reconciliation opens a read-write transaction of its own rather than inheriting the class-level
   * read-only default.
   */
  @Test
  void reconcileCommitsTheDerivedFlag_whenCalledWithoutAnAmbientTransaction() {
    SpaceStation station = new SpaceStation();
    station.setName(STATION_NAME);
    station.setHasRefinery(false);
    station.setHasRefineryTerminal(false);
    stationId = spaceStationRepository.save(station).getId();

    Terminal terminal = new Terminal();
    terminal.setName("Refinement Processing - Commit Probe");
    terminal.setType(Terminal.TYPE_REFINERY);
    terminal.setIsAvailableLive(true);
    terminal.setSpaceStationName(STATION_NAME);
    terminalId = terminalRepository.save(terminal).getId();

    service.reconcileRefineryTerminalFlags();

    assertTrue(
        spaceStationRepository.findByName(STATION_NAME).orElseThrow().getHasRefineryTerminal(),
        "reconcileRefineryTerminalFlags() must commit the derived flag outside an ambient"
            + " transaction — check that its @Transactional annotation is still present");
  }
}
