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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration of the mission notices raised by time (REQ-MISSION-025, prefix {@code
 * app.missions.notices}). A violation refuses to start the context.
 *
 * @param overdueAfter how long after its planned end a mission without an end time is reported as
 *     never ended; at least one hour, default {@code PT6H}
 */
@Validated
@ConfigurationProperties("app.missions.notices")
public record MissionNoticeProperties(
    @DefaultValue("PT6H") @NotNull @DurationMin(hours = 1) Duration overdueAfter) {}
