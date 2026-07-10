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

package de.greluc.krt.profit.basetool.frontend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration for the tool-wide live-sync relay (REQ-FE-015, ADR-0092).
 *
 * @param redis the cross-replica Redis pub/sub fan-out settings
 */
@Validated
@ConfigurationProperties("app.livesync")
public record LiveSyncProperties(@DefaultValue Redis redis) {

  /**
   * Cross-replica fan-out settings. The {@code enabled} flag also gates the {@code
   * LiveSyncRedisConfig} beans via {@code @ConditionalOnProperty} (default on: the frontend already
   * runs Redis for Spring Session, so single-instance-with-Redis uses the real fan-out and a Redis
   * outage degrades to local-only relay, never worse).
   *
   * @param enabled whether the Redis pub/sub fan-out is wired (default {@code true})
   * @param channel the Redis channel {@code changed} signals are published on
   */
  public record Redis(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("basetool:livesync:changed") @NotBlank String channel) {}
}
