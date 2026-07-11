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
 * Type-safe configuration for the cross-replica notification SSE Redis fan-out (REQ-FE-015,
 * ADR-0094).
 *
 * <p>Lives in the dependency-leaf {@code support} package (not {@code config}) to avoid a {@code
 * service}/{@code config} package cycle, like the other backend leaf
 * {@code @ConfigurationProperties} — registered by {@code @ConfigurationPropertiesScan} regardless
 * of package. The {@code enabled} flag also gates the {@code NotificationRedisConfig} beans and the
 * {@code LocalNotificationFanout}/{@code RedisNotificationFanout} selection via
 * {@code @ConditionalOnProperty}. Default off: the backend runs local-only SSE unless a deployment
 * explicitly turns the fan-out on (prod).
 *
 * @param enabled whether the Redis pub/sub fan-out is wired (default {@code false})
 * @param channel the Redis channel notification signals are published on
 */
@Validated
@ConfigurationProperties("app.notifications.redis-fanout")
public record NotificationFanoutProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("basetool:notify:published") @NotBlank String channel) {}
