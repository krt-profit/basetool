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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipCommands;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hangar's published ship writes, delegating to {@link HangarService} in the caller's
 * transaction.
 */
@Service
@RequiredArgsConstructor
public class HangarShipCommands implements ShipCommands {

  private final HangarService hangarService;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Ship addShipForClient(@NotNull UUID member, @NotNull ShipRequestDto ship) {
    return hangarService.addShipForClient(member, ship);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Ship updateShip(
      @NotNull UUID member, @NotNull UUID shipId, @NotNull ShipRequestDto ship) {
    return hangarService.updateShip(member, shipId, ship);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int deleteShip(@NotNull UUID member, @NotNull UUID shipId) {
    return hangarService.deleteShip(member, shipId);
  }
}
