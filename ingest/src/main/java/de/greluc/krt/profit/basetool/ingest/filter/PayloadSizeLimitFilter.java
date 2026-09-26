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

package de.greluc.krt.profit.basetool.ingest.filter;

import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.web.ProblemResponseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Rejects ingest requests whose body exceeds the configured cap with 413 (REQ-INGEST-005).
 *
 * <p>A declared {@code Content-Length} is checked up front; a chunked body is counted while read
 * and rejected once it crosses the cap, otherwise re-served to the controller from a bounded
 * buffer.
 */
@Component
@Slf4j
@Order(PayloadSizeLimitFilter.ORDER)
@RequiredArgsConstructor
public class PayloadSizeLimitFilter extends OncePerRequestFilter {

  /**
   * Runs after {@link RateLimitingFilter} and before Spring Security, so an over-budget request is
   * rejected before its body is read.
   */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

  /** Supplies the payload cap ({@code maxPayloadBytes}). */
  private final IngestProperties ingestProperties;

  /** Serializes the 413 problem body. */
  private final ObjectMapper objectMapper;

  /** Counts every 413 on {@code basetool_ingest_payload_rejected_total}. */
  private final MeterRegistry meterRegistry;

  /** Supplies the MDC key the problem body's {@code correlationId} is read from. */
  private final LoggingProperties loggingProperties;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    long max = ingestProperties.maxPayloadBytes();
    long declared = request.getContentLengthLong();

    if (declared > max) {
      reject(response, declared, max);
      return;
    }

    if (declared < 0) {
      byte[] body = readWithinCap(request.getInputStream(), max);
      if (body == null) {
        reject(response, declared, max);
        return;
      }
      filterChain.doFilter(new CachedBodyRequest(request, body), response);
      return;
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Writes the standard 413 {@code application/problem+json} response.
   *
   * @param response the response to populate
   * @param declaredBytes the declared {@code Content-Length}, or {@code -1} for a chunked body
   * @param maxBytes the configured cap the body exceeded
   * @throws IOException if writing the body fails
   */
  private void reject(@NotNull HttpServletResponse response, long declaredBytes, long maxBytes)
      throws IOException {
    meterRegistry.counter(MetricNames.INGEST_PAYLOAD_REJECTED).increment();
    log.debug(
        "Ingest payload rejected: declared={} bytes exceeds max={} bytes", declaredBytes, maxBytes);
    ProblemResponseWriter.write(
        response,
        objectMapper,
        loggingProperties,
        HttpStatus.CONTENT_TOO_LARGE,
        "Payload too large",
        "PAYLOAD_TOO_LARGE",
        "The ingest payload exceeds the allowed size.");
  }

  /**
   * Reads the stream up to {@code maxBytes}, stopping as soon as the cap is crossed.
   *
   * @param in the request body stream
   * @param maxBytes the inclusive cap
   * @return the buffered body, or {@code null} when it exceeds {@code maxBytes}
   * @throws IOException if the stream read fails
   */
  @Nullable
  private static byte[] readWithinCap(@NotNull InputStream in, long maxBytes) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    long total = 0;
    int read;
    while ((read = in.read(chunk)) != -1) {
      total += read;
      if (total > maxBytes) {
        return null;
      }
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  /**
   * Limits this filter to the ingest endpoints, decided on the decoded path via {@link
   * IngestPathScope}.
   *
   * @param request the current request
   * @return {@code true} for any path that is not under {@code /v1}
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return !IngestPathScope.isIngestRequest(request);
  }

  /**
   * Re-serves an already-read request body from memory so the controller can read it; the buffer is
   * adopted without copying.
   */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    /** The measured body; owned exclusively by this wrapper and never mutated. */
    private final byte[] body;

    /**
     * Wraps the request around its already-read body.
     *
     * @param request the original request whose stream was consumed
     * @param body the body bytes, adopted without copying; the caller must not retain or mutate
     *     them
     */
    CachedBodyRequest(@NotNull HttpServletRequest request, byte @NotNull [] body) {
      super(request);
      this.body = body;
    }

    @NotNull
    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream delegate = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return delegate.read();
        }

        @Override
        public int read(byte @NotNull [] buffer, int offset, int length) {
          return delegate.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
          return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
          throw new UnsupportedOperationException(
              "Async reads are not supported for ingest bodies");
        }
      };
    }

    @NotNull
    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }
  }
}
