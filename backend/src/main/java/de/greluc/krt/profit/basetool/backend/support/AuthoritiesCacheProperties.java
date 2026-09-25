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

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning for the authorities cache in {@code CustomJwtGrantedAuthoritiesConverter} (prefix {@code
 * app.security.authorities-cache}, fed by {@code APP_SECURITY_AUTHORITIES_CACHE_TTL}; REQ-SEC-056,
 * ADR-0174).
 *
 * <p>A cache miss runs a {@code syncUser} transaction plus several permission and scoping queries.
 *
 * @param ttl how long an assembled authority collection is reused per {@code (sub, token issuedAt,
 *     azp)} key; default five minutes, and a new token always misses
 */
@Validated
@ConfigurationProperties(prefix = "app.security.authorities-cache")
public record AuthoritiesCacheProperties(@DefaultValue("5m") @NotNull Duration ttl) {

  /**
   * Hard ceiling on {@code ttl}, enforced at startup by {@link #isTtlWithinBounds()}; it bounds how
   * long a revoked role, permission or membership stays effective for an issued token.
   */
  public static final Duration MAX_TTL = Duration.ofMinutes(15);

  /**
   * Validates at startup that {@code ttl} is strictly positive and does not exceed {@link
   * #MAX_TTL}.
   *
   * @return {@code true} when {@code ttl} is positive and at most {@link #MAX_TTL}, or when it is
   *     {@code null} (reported by its own {@code @NotNull})
   */
  @AssertTrue(
      message =
          "app.security.authorities-cache.ttl must be positive and at most 15 minutes (PT15M)")
  public boolean isTtlWithinBounds() {
    if (ttl == null) {
      return true;
    }
    return !ttl.isZero() && !ttl.isNegative() && ttl.compareTo(MAX_TTL) <= 0;
  }
}
