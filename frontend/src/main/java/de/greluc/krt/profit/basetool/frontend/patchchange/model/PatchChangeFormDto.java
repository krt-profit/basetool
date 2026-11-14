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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Mirror of what the admin's patch-change form starts from (REQ-PATCH-001, REQ-PATCH-002).
 *
 * @param suggestedLabel the latest P4K import's patch version, or {@code null} when none is known
 * @param previousLabel the latest patch change's label, or {@code null} before the first
 * @param previousLiveAt the latest patch change's live time, or {@code null} before the first
 * @param presets the options each patch kind presets, never {@code null} once constructed
 * @param homeLocations the curated home locations a home-location phase would offer, never {@code
 *     null} once constructed; empty when none is curated (REQ-PATCH-021)
 */
@DtoMirror
public record PatchChangeFormDto(
    String suggestedLabel,
    String previousLabel,
    Instant previousLiveAt,
    List<Preset> presets,
    List<HomeLocationOptionDto> homeLocations) {

  /** Copies the presets and the home locations, so a missing list reads as none. */
  public PatchChangeFormDto {
    presets = presets == null ? List.of() : List.copyOf(presets);
    homeLocations = homeLocations == null ? List.of() : List.copyOf(homeLocations);
  }

  /**
   * Returns the options a patch kind presets.
   *
   * @param kind the patch kind
   * @return the preset of that kind, or {@code null} when the backend sent none for it
   */
  @Nullable
  public PatchChangeOptions presetFor(@NotNull PatchKind kind) {
    return presets.stream()
        .filter(preset -> preset.kind() == kind)
        .map(Preset::options)
        .findFirst()
        .orElse(null);
  }

  /**
   * Mirror of the options a patch kind presets.
   *
   * @param kind the patch kind
   * @param options its preset
   */
  public record Preset(PatchKind kind, PatchChangeOptions options) {}
}
