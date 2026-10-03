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

package de.greluc.krt.profit.basetool.architecture.fixtures.layers;

import de.greluc.krt.profit.basetool.architecture.fixtures.layers.service.SomeService;
import org.mapstruct.Mapper;

/** Planted violation: a mapper that depends on a service. */
@Mapper
public interface FixtureMapper {

  /**
   * Maps through a service.
   *
   * @param service the service
   * @return the answer
   */
  default String map(SomeService service) {
    return service.answer();
  }
}
