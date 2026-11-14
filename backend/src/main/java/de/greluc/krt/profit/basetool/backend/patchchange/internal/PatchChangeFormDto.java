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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What the admin's patch-change form starts from (REQ-PATCH-001, REQ-PATCH-002).
 *
 * @param suggestedLabel the latest P4K import's patch version, or {@code null} when none is known
 * @param previousLabel the latest patch change's label, or {@code null} before the first
 * @param previousLiveAt the latest patch change's live time, the earliest a new live time may
 *     follow, or {@code null} before the first
 * @param presets the options each patch kind presets
 * @param groups the catalogue types each wipe group claims, in the groups' declaration order
 * @param homeLocations the curated home locations a home-location phase would offer; empty when
 *     none is curated, which the form warns about (REQ-PATCH-021)
 */
public record PatchChangeFormDto(
    @Nullable String suggestedLabel,
    @Nullable String previousLabel,
    @Nullable Instant previousLiveAt,
    @NotNull @Unmodifiable List<Preset> presets,
    @NotNull @Unmodifiable List<Group> groups,
    @NotNull @Unmodifiable List<HomeLocationOptionDto> homeLocations) {

  /**
   * The options a patch kind presets.
   *
   * @param kind the patch kind
   * @param options its preset
   */
  public record Preset(@NotNull PatchKind kind, @NotNull PatchChangeOptions options) {}

  /**
   * The catalogue types a wipe group claims (REQ-PATCH-010).
   *
   * @param group the wipe group
   * @param types its types; none for the other items, which take what no group claims
   */
  @Schema(name = "PatchWipeGroupTypes")
  public record Group(@NotNull WipeGroup group, @NotNull @Unmodifiable List<WipeGroupType> types) {}
}
