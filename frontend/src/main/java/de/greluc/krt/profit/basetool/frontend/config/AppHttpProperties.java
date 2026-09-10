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
 * Type-safe App Http configuration properties, bound through the canonical record constructor.
 * Component-level {@link DefaultValue} annotations preserve the previous field-initializer defaults
 * (connect 3s, the rest 5s) when the corresponding {@code app.http.*} key is absent.
 *
 * @param connectTimeout WebClient connect timeout
 * @param responseTimeout overall WebClient response timeout
 * @param readTimeout WebClient socket read timeout
 * @param writeTimeout WebClient socket write timeout
 * @param backendProtocol the wire protocol the request/response WebClient negotiates with the
 *     backend (ADR-0161 §8.1). {@code H2} offers HTTP/2 by ALPN and falls back to HTTP/1.1 when the
 *     server does not take it; {@code HTTP11} is the pre-2026-09 behaviour, kept as the way back
 *     without a redeploy. The SSE relay ignores this and stays on HTTP/1.1 — see {@code
 *     WebClientConfig#connector(boolean)}.
 * @param maxConcurrentStreams how many HTTP/2 streams this client opens on one backend connection
 *     before it opens another. Only read when {@code backendProtocol} is {@code H2}.
 * @param codec what the request/response client puts in its {@code Accept} header for backend reads
 *     (ADR-0161 §8.5). {@code CBOR} asks for {@code application/cbor} and falls back to JSON for
 *     anything the backend answers with a preset content type — RFC 7807 problems above all.
 *     Request bodies are unaffected and stay JSON either way.
 */
@Validated
@ConfigurationProperties(prefix = "app.http")
public record AppHttpProperties(
    @NotNull @DefaultValue("3s") Duration connectTimeout,
    @NotNull @DefaultValue("5s") Duration responseTimeout,
    @NotNull @DefaultValue("5s") Duration readTimeout,
    @NotNull @DefaultValue("5s") Duration writeTimeout,
    @NotNull @DefaultValue("H2") BackendProtocol backendProtocol,
    @Min(1) @Max(100) @DefaultValue("20") int maxConcurrentStreams,
    @NotNull @DefaultValue("CBOR") BackendCodec codec) {

  /**
   * The wire protocol offered on the frontend→backend hop.
   *
   * <p>An enum rather than a boolean because the fallback is not "off": {@code H2} configures
   * Reactor Netty with {@code H2, HTTP11} and lets ALPN choose, and a plain-{@code http://} backend
   * URL (the {@code test} profile) drops H2 from the list on its own rather than failing — Reactor
   * Netty only rejects the combination when H2 is the <em>sole</em> protocol.
   */
  public enum BackendProtocol {

    /** Offer HTTP/2 by ALPN, fall back to HTTP/1.1. The default since 2026-09-10. */
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
     * Ask for {@code application/cbor} first, {@code application/json} second. The default since
     * 2026-09-10.
     *
     * <p>The fallback is not decoration: a response whose content type the backend sets itself —
     * {@code application/problem+json}, a PDF, a CSV export — never reaches content negotiation at
     * all, and the JSON half is what decodes the problem bodies that carry the stable error {@code
     * code} the frontend routes on.
     */
    CBOR,

    /** Ask for {@code application/json} only, as this client did before ADR-0161 §8.5. */
    JSON
  }
}
