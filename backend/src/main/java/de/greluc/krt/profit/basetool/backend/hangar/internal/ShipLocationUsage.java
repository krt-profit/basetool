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

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationUsage;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reports a location as in use while a ship is stationed at it. */
@Component
@RequiredArgsConstructor
public class ShipLocationUsage implements LocationUsage {

  static final int POSITION = 10;

  private final ShipRepository shipRepository;

  @Override
  public int position() {
    return POSITION;
  }

  @Override
  public @NotNull String users() {
    return "ships";
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean isUsed(@NotNull UUID locationId) {
    return shipRepository.existsByLocationId(locationId);
  }
}
