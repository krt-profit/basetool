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

import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration under {@code app.request-body-limit.*} for the {@code RequestBodySizeLimitFilter} —
 * a hard cap on the size of a non-multipart JSON request body on the heavy import endpoints, so an
 * oversized array is refused with 413 BEFORE Jackson binds it into heap (security review,
 * memory-DoS).
 *
 * <p>Scoped to the listed paths only (the refinery screenshot-import extract by default) — the
 * everyday JSON write endpoints carry tiny bodies and are not affected. Multipart uploads (hangar /
 * blueprint / P4K imports) are handled by the separate {@code spring.servlet.multipart} cap and are
 * skipped by the filter.
 *
 * <p>Like {@link RateLimitProperties}, this lives in the dependency-leaf {@code support} package
 * (not {@code config}) so the {@code filter} layer can read it without a {@code filter} &rarr;
 * {@code config} package cycle; it is picked up by {@code @ConfigurationPropertiesScan} regardless
 * of package.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.request-body-limit")
public class RequestBodyLimitProperties {

  /** Whether the request-body-size cap is active. Disable only to diagnose a false rejection. */
  private boolean enabled = true;

  /**
   * Inclusive maximum request-body size in bytes for the covered paths. Defaults to 2&nbsp;MiB, the
   * same ceiling the frontend refinery proxy already enforces ({@code
   * RefineryImportProxyController.MAX_EXTRACT_BYTES}); a real extract is a few KB, so anything near
   * this is hostile or buggy. Must be at least 1&nbsp;KiB.
   */
  @Min(1024)
  private long maxBytes = 2L * 1024 * 1024;

  /**
   * Request URIs (exact match) whose non-multipart body is size-capped. Defaults to the refinery
   * screenshot-import extract endpoint — the one non-multipart JSON import flagged by the review;
   * add further JSON write paths here if they ever accept large bodies.
   */
  private List<String> paths = List.of("/api/v1/refinery-orders/import-extract");
}
