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
 * Settings of the app live-sync bridge's Redis fan-out (ADR-0143).
 *
 * <p>Off by default and on in production, mirroring {@link NotificationFanoutProperties}: Redis is
 * an optional enhancement for this backend, deliberately outside the readiness group (ADR-0084), so
 * a build or a test profile without it must start and serve normally. With the fan-out off, the
 * bridge still relays what this instance itself accepts; only the crossing to the web frontend and
 * to peer replicas stops.
 *
 * @param enabled whether to publish and consume {@code changed} frames over Redis
 * @param channel the channel the frames cross — <strong>the frontend's channel</strong>, not a
 *     second one. The whole bridge rests on both modules speaking on the same wire with the same
 *     payload, so overriding this to anything the frontend does not also use silently severs
 *     web-to-app propagation while leaving every health signal green.
 */
@Validated
@ConfigurationProperties("app.live-sync.redis-fanout")
public record LiveSyncFanoutProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("basetool:livesync:changed") @NotBlank String channel) {}
