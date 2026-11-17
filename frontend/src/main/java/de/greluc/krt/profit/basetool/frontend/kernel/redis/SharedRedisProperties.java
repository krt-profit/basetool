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

package de.greluc.krt.profit.basetool.frontend.kernel.redis;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the frontend reaches the shared Redis that carries live sync and the ingest handoff
 * (ADR-0388); the session store keeps {@code spring.data.redis.*}. Registered via {@code
 * ConfigurationPropertiesScan} on {@code FrontendApplication}.
 *
 * @param host the host of the shared Redis
 * @param port the port of the shared Redis
 * @param username the ACL user the frontend authenticates as; blank means Redis's {@code default}
 *     user
 * @param password the password of that user; blank means none
 */
@Validated
@ConfigurationProperties(prefix = "app.shared-redis")
public record SharedRedisProperties(
    @NotBlank String host,
    @Min(1) @Max(65_535) int port,
    @Nullable String username,
    @Nullable String password) {

  /**
   * Describes the connection without its password, so a logged or bound-and-reported instance never
   * carries the secret.
   *
   * @return the host, port and user
   */
  @Override
  public @NotNull String toString() {
    return "SharedRedisProperties[host=" + host + ", port=" + port + ", username=" + username + "]";
  }
}
