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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import de.greluc.krt.profit.basetool.frontend.mission.model.CreateMissionTemplateRequest;
import java.time.Instant;
import org.jetbrains.annotations.Nullable;

/**
 * The template editor's starting point taken from a readable mission.
 *
 * <p>The mission's times stay instants: the editor turns them into times of day in the viewer's
 * time zone.
 *
 * @param template the reusable parts, without times of day
 * @param meetingTime the mission's TeamSpeak meeting time, or {@code null}
 * @param plannedStartTime the mission's planned start, or {@code null}
 * @param plannedEndTime the mission's planned end, or {@code null}
 */
record MissionTemplateDraft(
    CreateMissionTemplateRequest template,
    @Nullable Instant meetingTime,
    @Nullable Instant plannedStartTime,
    @Nullable Instant plannedEndTime) {}
