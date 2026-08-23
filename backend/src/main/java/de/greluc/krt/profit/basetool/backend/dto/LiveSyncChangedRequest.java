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
 * A client's announcement that it changed something other members may be looking at (ADR-0143).
 *
 * <p>Carries no payload on purpose — only a room and the regions of it that moved. Every receiver
 * re-fetches through its own authorized read, which is what makes it safe for the emitter to be an
 * ordinary member rather than a trusted server path.
 *
 * @param topic the room, e.g. {@code inventory} or {@code mission:8f14…}. Rejected outright if it
 *     names no room this backend serves; the app must send back the canonical string it received
 *     from the stream rather than assembling one, so a client and the server never disagree about
 *     which room they are in.
 * @param sections the regions that changed. Keys outside the topic class's whitelist are dropped
 *     rather than rejected — a newer client naming a section this build has not heard of must still
 *     get its known sections through — but a list that clips to empty is refused, because relaying
 *     it would tell every receiver "something changed" with no way to narrow the reload.
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
