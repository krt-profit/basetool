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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.dto.uex.UexTerminalDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies that {@code UexUniverseSyncService.reconcileRefineryTerminalFlags()} recomputes {@code
 * has_refinery_terminal} on cities and space stations from the live {@code type = 'refinery'}
 * terminals, overriding UEX's {@code has_refinery} claim in both directions (REQ-REFINERY-020).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UexUniverseSyncRefineryFlagTest {

  @MockitoBean private UexClient uexClient;

  @Autowired private UexUniverseSyncService service;

  @Autowired private CityRepository cityRepository;

  @Autowired private SpaceStationRepository spaceStationRepository;

  @Autowired private TerminalRepository terminalRepository;

  /**
   * Builds a minimal terminal payload row.
   *
   * @param id UEX terminal id
   * @param name terminal name
   * @param type UEX terminal kind
   * @param stationName owning space station, or {@code null}
   * @param cityName owning city, or {@code null}
   * @param live UEX {@code is_available_live} state (1 = live)
   * @return the payload row
   */
  private static UexTerminalDto terminalDto(
      int id, String name, String type, String stationName, String cityName, int live) {
    return UexTerminalDto.builder()
        .id(id)
        .name(name)
        .type(type)
        .spaceStationName(stationName)
        .cityName(cityName)
        .isAvailableLive(live)
        .build();
  }

  /**
   * Persists a space station with the given UEX claim and a deliberately wrong starting derived
   * flag, so the assertions prove the reconciliation actually rewrote it.
   *
   * @param name station name
   * @param uexClaim UEX's raw {@code has_refinery} value
   * @param derivedBefore the stale derived value to start from
   */
  private void saveStation(String name, boolean uexClaim, boolean derivedBefore) {
    SpaceStation station = new SpaceStation();
    station.setName(name);
    station.setHasRefinery(uexClaim);
    station.setHasRefineryTerminal(derivedBefore);
    spaceStationRepository.save(station);
  }

  @Test
  void sweepDerivesRefineryFlagFromTerminalsNotFromTheUexClaim() {
    saveStation("MIC-L5 Modern Icarus Station", false, false);
    saveStation("People's Service Station Alpha", true, true);
    saveStation("MIC-L3 Endless Odyssey Station", false, false);

    City levski = new City();
    levski.setName("Levski");
    levski.setHasRefinery(true);
    levski.setHasRefineryTerminal(false);
    cityRepository.save(levski);

    when(uexClient.getTerminals())
        .thenReturn(
            fetched(
                List.of(
                    terminalDto(
                        244,
                        "Refinement Processing - MIC-L5",
                        Terminal.TYPE_REFINERY,
                        "MIC-L5 Modern Icarus Station",
                        null,
                        1),
                    terminalDto(
                        788,
                        "Refinement Center - Levski",
                        Terminal.TYPE_REFINERY,
                        null,
                        "Levski",
                        1),
                    terminalDto(
                        999, "Shop - MIC-L3", "item", "MIC-L3 Endless Odyssey Station", null, 1))));

    service.syncTerminals();
    service.reconcileRefineryTerminalFlags();

    assertTrue(
        spaceStationRepository
            .findByName("MIC-L5 Modern Icarus Station")
            .orElseThrow()
            .getHasRefineryTerminal());
    assertFalse(
        spaceStationRepository
            .findByName("People's Service Station Alpha")
            .orElseThrow()
            .getHasRefineryTerminal());
    assertFalse(
        spaceStationRepository
            .findByName("MIC-L3 Endless Odyssey Station")
            .orElseThrow()
            .getHasRefineryTerminal());
    assertTrue(cityRepository.findByName("Levski").orElseThrow().getHasRefineryTerminal());

    assertFalse(
        spaceStationRepository
            .findByName("MIC-L5 Modern Icarus Station")
            .orElseThrow()
            .getHasRefinery());

    assertTrue(
        Terminal.TYPE_REFINERY.equals(
            terminalRepository.findByIdTerminal(244).orElseThrow().getType()));
  }

  @Test
  void nonLiveRefineryTerminalDoesNotFlagItsStation() {
    saveStation("Retired Station", true, true);

    when(uexClient.getTerminals())
        .thenReturn(
            fetched(
                List.of(
                    terminalDto(
                        500,
                        "Refinement Processing - Retired",
                        Terminal.TYPE_REFINERY,
                        "Retired Station",
                        null,
                        0))));

    service.syncTerminals();
    service.reconcileRefineryTerminalFlags();

    assertFalse(
        spaceStationRepository
            .findByName("Retired Station")
            .orElseThrow()
            .getHasRefineryTerminal());
  }
}
