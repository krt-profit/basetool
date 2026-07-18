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

package de.greluc.krt.profit.basetool.ingest.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe, fail-fast configuration for the ingest gateway (prefix {@code app.ingest}). Validated
 * at startup ({@code @Validated}) so a missing backend URL or a nonsensical handoff TTL aborts the
 * boot instead of surfacing as a runtime 500 on the first call (REQ-INGEST-001/-003/-005).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.ingest")
public class IngestProperties {

  /**
   * Internal base URL of the backend resource server the gateway forwards to (e.g. {@code
   * https://backend:11261}). Reached over the internal Docker network only — the backend stays
   * internet-unreachable (REQ-INGEST-001).
   */
  @NotBlank @URL private String backendBaseUrl;

  /**
   * Public base URL of the frontend the browser is sent to after a successful ingest (e.g. {@code
   * https://app.profit-base.online}); used to build the {@code frontendUrl} returned to the
   * extractor.
   */
  @NotBlank @URL private String frontendBaseUrl;

  /**
   * Frontend path that renders the pre-filled refinery create form; the handoff id is appended as
   * {@code ?handoff=<id>} (REQ-INGEST-004).
   */
  @NotBlank private String refineryPath = "/refinery-orders/create";

  /**
   * Frontend path that renders the pre-filled personal-blueprint import preview; the handoff id is
   * appended as {@code ?handoff=<id>} (REQ-INGEST-004).
   */
  @NotBlank private String blueprintPath = "/personal-inventory/blueprints";

  /**
   * Lifetime of a staged handoff entry in Redis (REQ-INGEST-003). Kept short by design, but 30
   * minutes rather than the original 5: staging happens the moment the user clicks Send in the
   * extractor, whereas opening the pre-filled basetool page is a <em>separate</em> manual click
   * (plus a possible full browser login), so a 5-minute window could expire before pickup for a
   * slower user and surfaced as "Import-Link abgelaufen oder ungültig" on every send. The entry
   * stays single-use and per-subject scoped, so the longer window does not relax the replay / IDOR
   * guarantees. Overridable via the {@code APP_INGEST_HANDOFF_TTL} environment variable.
   */
  @NotNull private Duration handoffTtl = Duration.ofMinutes(30);

  /**
   * Hard upper bound on an accepted ingest payload, in bytes. Mirrors the frontend proxy's
   * 2&nbsp;MB cap — a real extract is a few KB; anything larger is rejected before forwarding
   * (REQ-INGEST-005).
   */
  @Min(1024)
  private long maxPayloadBytes = 2L * 1024 * 1024;
}
