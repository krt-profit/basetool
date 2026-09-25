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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe {@code app.http.*} settings for the backend WebClient; absent keys fall back to the
 * {@link DefaultValue}s (connect 3s, others 5s).
 *
 * @param connectTimeout WebClient connect timeout
 * @param responseTimeout overall WebClient response timeout
 * @param exportResponseTimeout response timeout applied per request to data-export downloads, which
 *     take long
 * @param readTimeout WebClient socket read timeout
 * @param writeTimeout WebClient socket write timeout
 * @param backendProtocol wire protocol negotiated with the backend (ADR-0161); the SSE relay always
 *     uses HTTP/1.1
 * @param maxConcurrentStreams HTTP/2 streams per backend connection; read only for {@code H2}
 * @param codec the {@code Accept} preference for backend reads; request bodies stay JSON
 * @param verifyBackendHostname whether the backend TLS hop also verifies the certificate's host
 *     name on top of the pinned chain (REQ-SEC-070); ignored under {@code dev} and {@code test}
 */
@Validated
@ConfigurationProperties(prefix = "app.http")
public record AppHttpProperties(
    @NotNull @DefaultValue("3s") Duration connectTimeout,
    @NotNull @DefaultValue("5s") Duration responseTimeout,
    @NotNull @DefaultValue("120s") Duration exportResponseTimeout,
    @NotNull @DefaultValue("5s") Duration readTimeout,
    @NotNull @DefaultValue("5s") Duration writeTimeout,
    @NotNull @DefaultValue("H2") BackendProtocol backendProtocol,
    @Min(1) @Max(100) @DefaultValue("20") int maxConcurrentStreams,
    @NotNull @DefaultValue("CBOR") BackendCodec codec,
    @DefaultValue("false") boolean verifyBackendHostname) {

  /**
   * The wire protocol offered on the frontend-to-backend hop. {@code H2} also offers HTTP/1.1, so a
   * plain {@code http://} backend URL still works.
   */
  public enum BackendProtocol {

    /** Offers HTTP/2 via ALPN with HTTP/1.1 as fallback; the default. */
    H2,

    /** Speak HTTP/1.1 only, as this client did before ADR-0161 §8.1. */
    HTTP11
  }

  /**
   * What the request/response client asks the backend to encode a read with.
   *
   * <p>Only the {@code Accept} header moves. The object model, the DTOs, the ~870 Bean Validation
   * annotations, the RFC 7807 handling, the filters and the metrics are all untouched — which is
   * the whole reason §8.5 names a binary <em>JSON</em> codec rather than a schema language.
   */
  public enum BackendCodec {

    /**
     * Requests {@code application/cbor} first and {@code application/json} second; the default. The
     * JSON fallback decodes responses with a preset content type, such as RFC 7807 problems.
     */
    CBOR,

    /** Ask for {@code application/json} only, as this client did before ADR-0161 §8.5. */
    JSON
  }
}
