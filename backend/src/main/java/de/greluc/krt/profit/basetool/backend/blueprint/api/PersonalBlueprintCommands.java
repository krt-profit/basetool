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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import de.greluc.krt.profit.basetool.backend.model.BlueprintSource;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The personal-blueprint writes other modules make (plan §5.3): each joins the caller's transaction
 * and records its {@code BLUEPRINT} audit event (REQ-AUDIT-001).
 */
public interface PersonalBlueprintCommands {

  /**
   * Adds one blueprint to the member's set as a manual addition; audited as {@code
   * BLUEPRINT_ADDED}.
   *
   * @param ownerUserId the owning member
   * @param request the product and the optional acquisition date and note
   * @return the added blueprint
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the product key
   *     names no active product
   */
  @NotNull
  PersonalBlueprintResponse add(
      @NotNull UUID ownerUserId, @NotNull PersonalBlueprintCreateRequest request);

  /**
   * Adds one blueprint to the member's set, recording where it came from; audited as {@code
   * BLUEPRINT_ADDED}.
   *
   * @param ownerUserId the owning member
   * @param request the product and the optional acquisition date and note
   * @param source where the blueprint came from
   * @param sourceClientId the connected application, or {@code null}
   * @return the added blueprint
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the product key
   *     names no active product
   */
  @NotNull
  PersonalBlueprintResponse add(
      @NotNull UUID ownerUserId,
      @NotNull PersonalBlueprintCreateRequest request,
      @NotNull BlueprintSource source,
      @Nullable String sourceClientId);

  /**
   * Removes one of the member's blueprints; audited as {@code BLUEPRINT_REMOVED}.
   *
   * @param ownerUserId the owning member
   * @param id the blueprint
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the entry is
   *     missing or owned by someone else
   */
  void delete(@NotNull UUID ownerUserId, @NotNull UUID id);
}
