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
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe, fail-fast configuration for the ingest gateway (prefix {@code app.ingest}). Bound once
 * through the canonical record constructor and validated at startup ({@code @Validated}), so a
 * missing backend URL or a nonsensical handoff TTL aborts the boot instead of surfacing as a
 * runtime 500 on the first call (REQ-INGEST-001/-003/-005).
 *
 * @param backendBaseUrl internal base URL of the backend resource server the gateway forwards to
 *     (e.g. {@code https://backend:11261}); reached over the internal network only — the backend
 *     stays internet-unreachable (REQ-INGEST-001)
 * @param frontendBaseUrl public base URL of the frontend the browser is sent to after a successful
 *     ingest (e.g. {@code https://app.profit-base.online}); used to build the {@code frontendUrl}
 *     returned to the extractor
 * @param publicBaseUrl the gateway's own externally reachable origin, e.g. {@code
 *     https://ingest.profit-base.online}. Used only to build the DPoP {@code htu} comparison
 *     target, so the comparison does not depend on the reverse proxy's forwarded headers (ADR-0129,
 *     {@link PublicUriDpopAuthenticationConverter}). Blank keeps Spring's stock request-derived
 *     target
 * @param refineryPath frontend path that renders the pre-filled refinery create form; the handoff
 *     id is appended as {@code ?handoff=<id>} (REQ-INGEST-004)
 * @param blueprintPath frontend path that renders the pre-filled personal-blueprint import preview;
 *     the handoff id is appended as {@code ?handoff=<id>} (REQ-INGEST-004)
 * @param handoffTtl lifetime of a staged handoff entry in Redis (REQ-INGEST-003). 30 minutes rather
 *     than the original 5: staging happens the moment the user clicks Send, whereas opening the
 *     pre-filled page is a <em>separate</em> manual click (plus a possible full browser login), so
 *     a 5-minute window expired before pickup for slower users. The entry stays single-use and
 *     per-subject scoped. Overridable via {@code APP_INGEST_HANDOFF_TTL}
 * @param maxPayloadBytes hard upper bound on an accepted ingest payload, in bytes; mirrors the
 *     frontend proxy's 2&nbsp;MB cap (REQ-INGEST-005)
 * @param maxHandoffBytes hard upper bound on a single <em>staged</em> handoff document, in bytes.
 *     Deliberately far below {@code maxPayloadBytes}: the staging store shares the Redis that holds
 *     the frontend's Spring Session store under {@code --maxmemory-policy noeviction}, where
 *     reaching the ceiling refuses writes, i.e. nobody can log in any more. Overridable via {@code
 *     APP_INGEST_MAX_HANDOFF_BYTES}
 * @param maxHandoffsPerSubject maximum number of live staged handoffs per subject; the oldest are
 *     evicted beyond it, turning an unbounded per-subject footprint into {@code
 *     maxHandoffsPerSubject × maxHandoffBytes}. Overridable via {@code
 *     APP_INGEST_MAX_HANDOFFS_PER_SUBJECT}
 */
@Validated
@ConfigurationProperties(prefix = "app.ingest")
public record IngestProperties(
    @NotBlank @URL String backendBaseUrl,
    @NotBlank @URL String frontendBaseUrl,
    @DefaultValue("") String publicBaseUrl,
    @NotBlank @DefaultValue("/refinery-orders/create") String refineryPath,
    @NotBlank @DefaultValue("/personal-inventory/blueprints") String blueprintPath,
    @NotNull @DefaultValue("PT30M") Duration handoffTtl,
    @Min(1024) @DefaultValue("2097152") long maxPayloadBytes,
    @Min(1024) @DefaultValue("262144") long maxHandoffBytes,
    @Min(1) @DefaultValue("10") int maxHandoffsPerSubject) {

  /**
   * Normalises an absent public origin to empty, the documented "not configured" value, so {@link
   * PublicUriDpopAuthenticationConverter} can test it with {@code isBlank()} alone.
   */
  public IngestProperties {
    publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
  }
}
