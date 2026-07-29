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
 * Pins that {@code UexUniverseSyncService.reconcileRefineryTerminalFlags()} actually COMMITS its
 * derived flags when called the way production calls it — from {@code UexScheduler}'s {@code
 * finally}, with no ambient transaction (REQ-REFINERY-020).
 *
 * <p>Deliberately NOT annotated {@code @Transactional}, which is the entire point and the reason
 * this lives apart from {@link UexUniverseSyncRefineryFlagTest}. That sibling class is
 * {@code @Transactional}, so the method under test merely joins the test's own read-write
 * transaction and passes regardless of its own transaction settings. Without an outer transaction
 * the Spring proxy applies whatever the method declares, and {@code UexUniverseSyncService} is
 * annotated {@code @Transactional(readOnly = true)} at class level: were the explicit
 * {@code @Transactional} on the method ever dropped, Hibernate would switch the new transaction to
 * {@code FlushMode.MANUAL} and discard the flag writes <em>silently</em> — no exception, no log,
 * and an empty refinery picker in production while every other test stayed green.
 *
 * <p>Writes are committed for real here, so the fixture is removed in {@link #cleanUp()} rather
 * than rolled back.
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

    // Re-read in a fresh transaction: a read-only reconciliation would have dropped this write on
    // the floor and the flag would still be false.
    assertTrue(
        spaceStationRepository.findByName(STATION_NAME).orElseThrow().getHasRefineryTerminal(),
        "reconcileRefineryTerminalFlags() must commit the derived flag outside an ambient"
            + " transaction — check that its @Transactional annotation is still present");
  }
}
