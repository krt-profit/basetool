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

package de.greluc.krt.profit.basetool.frontend.promotion.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.model.SquadronReferenceDto;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend DTO for a promotion topic, mirroring the backend {@code PromotionTopicResponse}.
 *
 * @param id topic id
 * @param version optimistic-lock version
 * @param name display name
 * @param description optional description
 * @param sortOrder position among the topics
 * @param owningSquadron the owning squadron, or {@code null} when the topic has none
 * @param createdAt creation time
 * @param updatedAt last modification time
 */
@DtoMirror
public record PromotionTopicDto(
    UUID id,
    Long version,
    String name,
    String description,
    int sortOrder,
    @Nullable SquadronReferenceDto owningSquadron,
    Instant createdAt,
    Instant updatedAt) {}
