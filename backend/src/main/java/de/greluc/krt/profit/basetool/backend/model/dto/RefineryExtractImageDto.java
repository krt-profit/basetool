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

package de.greluc.krt.profit.basetool.backend.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Metadata of one source screenshot of an extracted order; the latest {@code capturedAt} becomes
 * the draft's {@code startedAt} (REQ-REFINERY-017).
 *
 * @param name screenshot file name on the user's machine
 * @param width capture width in pixels
 * @param height capture height in pixels
 * @param cropMode how the panel was isolated: {@code vlm}, {@code manual} or {@code precropped}
 * @param capturedAt UTC capture instant, or {@code null} when unknown
 */
public record RefineryExtractImageDto(
    @NotNull @Size(max = 255) String name,
    @Positive Integer width,
    @Positive Integer height,
    @Size(max = 32) String cropMode,
    Instant capturedAt) {}
