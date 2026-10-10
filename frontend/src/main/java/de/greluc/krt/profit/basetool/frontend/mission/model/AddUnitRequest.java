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

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.UUID;

/**
 * Write payload for adding a unit to a mission.
 *
 * @param name the unit name
 * @param shipTypeId the ship type, or {@code null}
 * @param shipId the pinned ship, or {@code null}
 * @param highValueUnit whether the unit is a high-value unit; {@code null} counts as {@code false}
 * @param frequency the unit's radio frequency, or {@code null}
 * @param responsibleUserId the responsible member, or {@code null}
 * @param note the unit note, or {@code null}
 */
@DtoMirror
public record AddUnitRequest(
    String name,
    UUID shipTypeId,
    UUID shipId,
    Boolean highValueUnit,
    Double frequency,
    UUID responsibleUserId,
    String note) {}
