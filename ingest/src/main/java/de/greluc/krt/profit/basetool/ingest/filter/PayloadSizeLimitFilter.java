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
 * Rejects ingest requests whose body exceeds the configured cap before it is relayed to the backend
 * (REQ-INGEST-005) — the gateway mirror of the frontend proxy's 2&nbsp;MB guard. A real extract is
 * a few KB; a larger body is almost certainly hostile or buggy, so it is refused with 413 instead
 * of being buffered and streamed onward.
 *
 * <p>An honestly-declared {@code Content-Length} is checked up front. A {@code Transfer-Encoding:
 * chunked} request carries no {@code Content-Length} ({@code getContentLengthLong()} returns {@code
 * -1}), which an attacker could otherwise use to slip an arbitrarily large body past a length-only
 * check and exhaust the gateway heap (security audit INGEST-DOS-1). For that case the body is
 * counted as it is read and rejected the moment it crosses the cap; a within-cap body is buffered
 * (bounded by the cap) and re-served to the controller unchanged.
 */
@Component
@Slf4j
@Order(PayloadSizeLimitFilter.ORDER)
@RequiredArgsConstructor
public class PayloadSizeLimitFilter extends OncePerRequestFilter {

  /** After correlation id, before rate limit and Spring Security. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

  private final IngestProperties ingestProperties;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    long max = ingestProperties.getMaxPayloadBytes();
    long declared = request.getContentLengthLong();

    // Fast path: an honestly-declared oversized body is rejected without reading it.
    if (declared > max) {
      reject(response);
      return;
    }

    // Chunked / unknown-length body: the declared check above cannot see its real size, so count
    // it while reading and reject once it crosses the cap.
    if (declared < 0) {
      byte[] body = readWithinCap(request.getInputStream(), max);
      if (body == null) {
        reject(response);
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
   * @throws IOException if writing the body fails
   */
  private void reject(@NotNull HttpServletResponse response) throws IOException {
    // REQ-OBS-011: the DoS guard was silent (no log, no metric) unlike the sibling bot / rate-limit
    // filters — count and DEBUG-log each 413 so a flood of oversized-body probes is detectable.
    meterRegistry.counter(MetricNames.INGEST_PAYLOAD_REJECTED).increment();
    log.debug("Ingest payload rejected: body exceeds the configured cap");
    ProblemResponseWriter.write(
        response,
        objectMapper,
        HttpStatus.CONTENT_TOO_LARGE,
        "Payload too large",
        "PAYLOAD_TOO_LARGE",
        "The ingest payload exceeds the allowed size.");
  }

  /**
   * Reads up to {@code maxBytes} from the stream, returning the buffered bytes — or {@code null}
   * when the stream carries more than {@code maxBytes}, signalling the caller to reject with 413.
   * At most {@code maxBytes} plus one read-buffer worth of data is ever held in memory, and reading
   * stops the moment the cap is crossed so a hostile body is never fully buffered.
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
   * Limits this filter to the ingest endpoints; other paths (actuator, api-docs) are unaffected.
   *
   * @param request the current request
   * @return {@code true} for any path that is not under {@code /v1/}
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/v1/");
  }

  /**
   * Re-serves an already-counted request body to downstream handlers from an in-memory buffer, so
   * the controller can still read a chunked body the filter had to consume to measure it.
   */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(@NotNull HttpServletRequest request, byte @NotNull [] body) {
      super(request);
      this.body = body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream delegate = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return delegate.read();
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

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }
  }
}
