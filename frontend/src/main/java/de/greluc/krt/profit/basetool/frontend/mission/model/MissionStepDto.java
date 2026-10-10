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

package de.greluc.krt.profit.basetool.frontend.mission.model;

import java.util.UUID;

/**
 * Frontend mirror of the backend's {@code MissionStepDto} — one step of a mission's Ablauf
 * (procedure timeline).
 *
 * @param id the step id
 * @param title the required step title (checklist line label)
 * @param meta the optional free-text "Zeit / Ort" hint, or {@code null}
 * @param done the shared completion flag every viewer sees
 * @param orderIndex the zero-based position within the mission's Ablauf
 */
public record MissionStepDto(UUID id, String title, String meta, boolean done, int orderIndex) {}
