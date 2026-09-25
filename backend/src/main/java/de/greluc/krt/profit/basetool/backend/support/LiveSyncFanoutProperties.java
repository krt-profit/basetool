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

package de.greluc.krt.profit.basetool.backend.support;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the app live-sync bridge's Redis fan-out (ADR-0143); off by default.
 *
 * @param enabled whether to publish and consume {@code changed} frames over Redis
 * @param channel the channel the frames cross; must be the frontend's channel, or web-to-app
 *     propagation silently stops
 */
@Validated
@ConfigurationProperties("app.live-sync.redis-fanout")
public record LiveSyncFanoutProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("basetool:livesync:changed") @NotBlank String channel) {}
