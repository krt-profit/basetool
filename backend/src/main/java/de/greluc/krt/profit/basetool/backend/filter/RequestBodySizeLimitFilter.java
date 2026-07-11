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

package de.greluc.krt.profit.basetool.backend.filter;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.RequestBodyLimitProperties;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects an oversized non-multipart request body on the configured heavy JSON import paths with
 * 413 BEFORE Spring MVC binds it, so a multi-million-element array cannot be materialised in heap
 * only to be thrown away by Jakarta {@code @Size} validation afterwards (security review,
 * memory-DoS on the refinery {@code import-extract} endpoint).
 *
 * <p>An honestly-declared {@code Content-Length} over the cap is rejected without reading the body.
 * A {@code Transfer-Encoding: chunked} body carries no {@code Content-Length} ({@code
 * getContentLengthLong()} returns {@code -1}), which could otherwise smuggle an unbounded body past
 * a length-only check; for that case the body is counted as it is read and rejected the instant it
 * crosses the cap, holding at most the cap (plus one read buffer) in memory. A within-cap chunked
 * body is buffered and re-served to the controller unchanged.
 *
 * <p>Scoped to {@link RequestBodyLimitProperties#getPaths()} only (the refinery extract by default)
 * and skips multipart uploads (handled by the {@code spring.servlet.multipart} cap), so ordinary
 * small JSON writes and the multipart imports are unaffected.
 */
@Slf4j
@RequiredArgsConstructor
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

  private final RequestBodyLimitProperties properties;
  private final AppProblemProperties problemProperties;
  private final MeterRegistry meterRegistry;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    long max = properties.getMaxBytes();
    long declared = request.getContentLengthLong();

    if (declared > max) {
      reject(response, request);
      return;
    }
    if (declared < 0) {
      byte[] body = readWithinCap(request.getInputStream(), max);
      if (body == null) {
        reject(response, request);
        return;
      }
      filterChain.doFilter(new CachedBodyRequest(request, body), response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Skips every request the cap does not apply to: the filter is disabled, the path is not in the
   * configured set, the request has no body ({@code GET}/{@code HEAD}/{@code DELETE}), or it is a
   * multipart upload (bounded separately by the multipart cap).
   *
   * @param request the current request
   * @return {@code true} to bypass the filter
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    if (!properties.isEnabled() || !properties.getPaths().contains(request.getRequestURI())) {
      return true;
    }
    String contentType = request.getContentType();
    return contentType != null
        && contentType.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
  }

  /**
   * Writes the 413 {@code application/problem+json} response and counts the rejection on {@code
   * basetool_request_body_rejected_total} (REQ-OBS-011) so a flood of oversized-body probes is
   * observable rather than silent.
   *
   * @param response the response to populate
   * @param request the request being rejected (its URI is echoed as the problem {@code instance})
   * @throws IOException if writing the body fails
   */
  private void reject(@NotNull HttpServletResponse response, @NotNull HttpServletRequest request)
      throws IOException {
    meterRegistry.counter(MetricNames.REQUEST_BODY_REJECTED).increment();
    String correlationId = UUID.randomUUID().toString();
    log.debug(
        "Request body rejected: body exceeds the {}-byte cap on {}, correlationId={}",
        properties.getMaxBytes(),
        request.getRequestURI(),
        correlationId);
    String body =
        "{\"type\":\""
            + problemProperties.getBaseUri()
            + "request-body-too-large\",\"title\":\"Payload Too Large\",\"status\":413,"
            + "\"detail\":\"The request body exceeds the allowed size for this endpoint.\","
            + "\"code\":\"REQUEST_BODY_TOO_LARGE\",\"instance\":\""
            + jsonEscape(request.getRequestURI())
            + "\",\"correlationId\":\""
            + correlationId
            + "\"}";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    // Set the header directly and write UTF-8 bytes so the media type stays a clean
    // application/problem+json without a `;charset=` suffix (matches RateLimitingFilter).
    response.setHeader("Content-Type", "application/problem+json");
    response.setHeader("X-Correlation-Id", correlationId);
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
  }

  /**
   * Reads up to {@code maxBytes} from the stream, returning the buffered bytes — or {@code null}
   * once the stream exceeds {@code maxBytes}, signalling the caller to reject with 413. At most
   * {@code maxBytes} plus one read buffer is ever held, and reading stops the instant the cap is
   * crossed.
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
   * Minimal JSON string escaping for the {@code instance} value (the request URI), covering the
   * characters that would break the hand-built problem+json body.
   *
   * @param value the raw value
   * @return the value with {@code \}, {@code "} and control whitespace escaped
   */
  private static String jsonEscape(@NotNull String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
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
          throw new UnsupportedOperationException("Async reads are not supported here");
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
