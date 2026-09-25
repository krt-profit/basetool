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

package de.greluc.krt.profit.basetool.backend.dto;

import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopicClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Announces that a live-sync room's regions changed, so peers re-fetch them (ADR-0143).
 *
 * <p>Carries no payload; every receiver re-fetches through its own authorized read.
 *
 * @param topic the canonical room string received from the stream, e.g. {@code inventory}; an
 *     unknown room is rejected
 * @param sections the changed regions; unknown keys are dropped, a list that clips to empty is
 *     refused
 */
@Schema(description = "Announces that a live-sync room's regions changed, so peers re-fetch them.")
public record LiveSyncChangedRequest(
    @Schema(
            description = "The live-sync room that changed.",
            example = "mission:8f14e45f-ceea-467a-9c5b-5f1f52a3a1c2")
        @NotBlank
        @Size(max = LiveSyncTopic.MAX_LENGTH)
        String topic,
    @Schema(description = "The regions of the room that changed.", example = "[\"crew\"]")
        @NotEmpty
        @Size(max = LiveSyncTopicClass.MAX_SECTIONS_PER_FRAME)
        List<@NotBlank @Size(max = 64) String> sections) {}
