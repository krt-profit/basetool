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

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The scope module's {@link ActiveOrgUnitProvider}: the org unit {@link
 * RequestScopeResolver#currentSquadronId()} filters the current request by.
 */
@Service
@RequiredArgsConstructor
public class ScopeActiveOrgUnitProvider implements ActiveOrgUnitProvider {

  private final RequestScopeResolver requestScopeResolver;

  /**
   * The admin's header pin or the member's persistent org unit.
   *
   * @return the org unit id, or empty when no filter applies
   */
  @Override
  @NotNull
  public Optional<UUID> activeOrgUnitId() {
    return requestScopeResolver.currentSquadronId();
  }
}
