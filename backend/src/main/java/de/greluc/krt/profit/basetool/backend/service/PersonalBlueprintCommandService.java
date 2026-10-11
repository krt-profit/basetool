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

import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintCommands;
import de.greluc.krt.profit.basetool.backend.model.BlueprintSource;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The blueprint's published personal-blueprint writes, delegating to {@link
 * PersonalBlueprintService} in the caller's transaction.
 */
@Service
@RequiredArgsConstructor
public class PersonalBlueprintCommandService implements PersonalBlueprintCommands {

  private final PersonalBlueprintService personalBlueprintService;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PersonalBlueprintResponse add(
      @NotNull UUID ownerUserId, @NotNull PersonalBlueprintCreateRequest request) {
    return personalBlueprintService.add(ownerUserId, request);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PersonalBlueprintResponse add(
      @NotNull UUID ownerUserId,
      @NotNull PersonalBlueprintCreateRequest request,
      @NotNull BlueprintSource source,
      @Nullable String sourceClientId) {
    return personalBlueprintService.add(ownerUserId, request, source, sourceClientId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void delete(@NotNull UUID ownerUserId, @NotNull UUID id) {
    personalBlueprintService.delete(ownerUserId, id);
  }
}
