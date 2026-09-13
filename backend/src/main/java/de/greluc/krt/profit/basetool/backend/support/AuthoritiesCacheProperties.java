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
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning for the authorities memoisation in {@code CustomJwtGrantedAuthoritiesConverter} (prefix
 * {@code app.security.authorities-cache}, fed by {@code APP_SECURITY_AUTHORITIES_CACHE_TTL} —
 * REQ-SEC-056, ADR-0174).
 *
 * <p>This is the single knob that decides how much load the authorization path puts on the
 * database. Every authenticated request runs the converter; a cache <em>miss</em> costs a
 * write-capable {@code syncUser} transaction plus five to eight SELECTs against the permission and
 * scoping tables, one of them a role lookup per realm role. A production measurement on 2026-09-13
 * attributed 84 million sequential scans across six tables to that miss traffic, and it was the
 * dominant driver of CFS throttling on the {@code db-backend} container.
 *
 * <p>Lives in {@code support} rather than {@code config} deliberately: {@code config} already
 * depends on {@code service} (the security configuration wires the converter), so a properties
 * class the {@code service} layer reads would close a package cycle that {@code
 * ArchitectureTest.backendPackagesShouldBeFreeOfDependencyCycles} forbids. Registered via
 * {@code @ConfigurationPropertiesScan} on {@code BackendApplication}, which scans regardless of
 * package.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.security.authorities-cache")
public class AuthoritiesCacheProperties {

  /**
   * Hard ceiling on {@link #ttl}, enforced at startup by {@link #isTtlWithinBounds()}.
   *
   * <p>The TTL is a security-relevant staleness window: until it expires, a revoked role, a
   * withdrawn permission, a reversed approval or a removed org-unit membership stays effective for
   * an already-issued token. Fifteen minutes keeps that window inside the operational expectation
   * that a revocation takes effect within a few minutes without a forced logout, and refusing to
   * start beyond it means a mistyped value cannot quietly widen the window.
   */
  public static final Duration MAX_TTL = Duration.ofMinutes(15);

  /**
   * How long an assembled authority collection is reused for a given {@code (sub, token issuedAt,
   * azp)} key before the next request re-runs the full resolution.
   *
   * <p>Defaults to five minutes (ADR-0174), raised from the original hard-coded 30 seconds. Roles,
   * permissions and memberships change on the order of once a week, so a 30-second window made an
   * actively clicking member pay the full query storm twice a minute for facts that had not moved.
   *
   * <p>A fresh login always misses regardless of this value, because the token's {@code issuedAt}
   * is part of the cache key — so a re-authentication picks up new authorities immediately, and
   * this TTL only bounds staleness <em>within</em> one token's life.
   */
  @NotNull private Duration ttl = Duration.ofMinutes(5);

  /**
   * Validates that {@link #ttl} is strictly positive and does not exceed {@link #MAX_TTL}.
   *
   * <p>Runs at startup because the class is {@code @Validated}: a zero or negative value would
   * disable the cache and silently restore the query storm this property exists to bound, and a
   * value above the ceiling would widen the revocation window past what the access model assumes.
   * Both fail the context rather than degrading at run time.
   *
   * @return {@code true} when {@link #ttl} is positive and at most {@link #MAX_TTL}, or when it is
   *     {@code null} — the {@code null} case is reported by {@link #ttl}'s own {@code @NotNull}, so
   *     this check does not duplicate that message
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
