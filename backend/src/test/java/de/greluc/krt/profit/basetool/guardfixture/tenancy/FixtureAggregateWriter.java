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

package de.greluc.krt.profit.basetool.guardfixture.tenancy;

import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/**
 * Fixture service that writes the marked aggregate, one method with and one without a scope check.
 */
@RequiredArgsConstructor
public class FixtureAggregateWriter {

  /** The repository written to. */
  private final FixtureAggregateRepository repository;

  /** The scope service consulted by the checked write. */
  private final OwnerScopeService ownerScopeService;

  /**
   * Saves the aggregate without consulting the scope service.
   *
   * @param id the aggregate id
   */
  public void touch(UUID id) {
    repository.save(repository.findById(id).orElseThrow());
  }

  /**
   * Saves the aggregate after a scope check.
   *
   * @param id the aggregate id
   */
  public void touchChecked(UUID id) {
    if (!ownerScopeService.canEditMission(id)) {
      throw new IllegalStateException("out of scope");
    }
    repository.save(repository.findById(id).orElseThrow());
  }
}
