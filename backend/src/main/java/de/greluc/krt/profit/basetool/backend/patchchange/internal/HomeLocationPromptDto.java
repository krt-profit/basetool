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

import de.greluc.krt.profit.basetool.backend.patchchange.api.MemberRelocationState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A member's home-location prompt (REQ-PATCH-021, REQ-PATCH-022): where the member stands in the
 * latest home-location phase, the home locations on offer and what the relocation would move.
 *
 * @param state where the member stands
 * @param patchChangeId the phase's patch change, or {@code null} when no phase ever asked
 * @param label its label, or {@code null} when no phase ever asked
 * @param liveAt its live time, or {@code null} when no phase ever asked
 * @param chosen the chosen home location, or {@code null} before the choice or once deleted
 * @param chosenAt when the member relocated, or {@code null} before the choice
 * @param locations the curated home locations on offer
 * @param preview what moves, per current location; empty unless the member is pending
 */
public record HomeLocationPromptDto(
    @NotNull MemberRelocationState state,
    @Nullable UUID patchChangeId,
    @Nullable String label,
    @Nullable Instant liveAt,
    @Nullable HomeLocationOptionDto chosen,
    @Nullable Instant chosenAt,
    @NotNull @Unmodifiable List<HomeLocationOptionDto> locations,
    @NotNull @Unmodifiable List<HomeLocationPreviewDto> preview) {}
