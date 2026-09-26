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
 * Fail-fast configuration of the ingest gateway ({@code app.ingest}), validated at startup
 * (REQ-INGEST-001/-003/-005).
 *
 * @param backendBaseUrl internal base URL of the backend the gateway forwards to
 * @param frontendBaseUrl public frontend base URL used to build the {@code frontendUrl} returned to
 *     the extractor
 * @param publicBaseUrl the gateway's external origin, used only as the DPoP {@code htu} comparison
 *     target (ADR-0129); blank keeps the request-derived target
 * @param refineryPath frontend path of the pre-filled refinery form; {@code ?handoff=<id>} is
 *     appended (REQ-INGEST-004)
 * @param blueprintPath frontend path of the pre-filled blueprint import preview; {@code
 *     ?handoff=<id>} is appended (REQ-INGEST-004)
 * @param handoffTtl lifetime of a single-use staged handoff entry in Redis (REQ-INGEST-003)
 * @param maxPayloadBytes upper bound on an accepted ingest payload, in bytes (REQ-INGEST-005)
 * @param maxHandoffBytes upper bound on one staged handoff document, in bytes; kept small because
 *     the staging Redis also holds the frontend sessions
 * @param maxHandoffsPerSubject maximum live staged handoffs per subject; the oldest are evicted
 * @param verifyBackendHostname whether the backend relay also verifies the certificate's host name
 *     on top of the pinned chain (REQ-SEC-070, ADR-0211); ignored under {@code dev}/{@code test}
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
    @Min(1) @DefaultValue("10") int maxHandoffsPerSubject,
    @DefaultValue("false") boolean verifyBackendHostname) {

  /**
   * Normalises an absent public origin to empty, the documented "not configured" value, so {@link
   * PublicUriDpopAuthenticationConverter} can test it with {@code isBlank()} alone.
   */
  public IngestProperties {
    publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
  }
}
