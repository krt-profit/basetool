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

package de.greluc.krt.profit.basetool.backend.mission.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.FrequencyTypeDto;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Data transfer record carrying Mission Frequency payload.
 *
 * <p>Exactly one of {@code frequencyType} (a global type) or {@code name} (a custom,
 * mission-specific label) is populated, mirroring the dual-mode {@code MissionFrequency} entity.
 *
 * @param id the frequency row id.
 * @param frequencyType the referenced global frequency type, or {@code null} for a custom channel.
 * @param name the custom channel label, or {@code null} for a typed channel.
 * @param value the frequency value (max 999.99, two decimals).
 * @param version the optimistic-lock version echoed back on edits.
 */
public record MissionFrequencyDto(
    UUID id, FrequencyTypeDto frequencyType, String name, BigDecimal value, Long version) {}
