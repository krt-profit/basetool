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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

/**
 * Rejects an oversized non-multipart request body on the configured JSON import paths with 413
 * before Spring MVC binds it.
 *
 * <p>An over-cap {@code Content-Length} is rejected without reading; a chunked body is counted
 * while read and rejected once it crosses the cap, otherwise buffered and re-served unchanged.
 * Paths are matched as {@link PathPattern}s against the decoded request path.
 */
@Slf4j
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

  private final RequestBodyLimitProperties properties;
  private final AppProblemProperties problemProperties;
  private final MeterRegistry meterRegistry;

  /** The configured capped paths, compiled once; a literal entry matches exactly that path. */
  private final List<PathPattern> cappedPaths;

  /**
   * Compiles {@link RequestBodyLimitProperties#getPaths()} into {@link PathPattern}s, failing
   * startup on a malformed entry.
   *
   * @param properties the validated cap configuration (enabled flag, byte cap, capped paths)
   * @param problemProperties RFC 7807 problem-type base URI for the 413 body
   * @param meterRegistry registry for the {@code basetool_request_body_rejected_total} counter
   * @throws IllegalStateException when a configured path is blank or invalid {@link PathPattern}
   *     syntax
   */
  public RequestBodySizeLimitFilter(
      RequestBodyLimitProperties properties,
      AppProblemProperties problemProperties,
      MeterRegistry meterRegistry) {
    this.properties = properties;
    this.problemProperties = problemProperties;
    this.meterRegistry = meterRegistry;
    this.cappedPaths = compilePaths(properties.paths());
  }

  /**
   * Compiles the configured capped paths, failing fast on a malformed entry.
   *
   * @param paths the raw configured paths, possibly {@code null} or empty
   * @return the compiled patterns, unmodifiable because the field is shared across request threads
   * @throws IllegalStateException when an entry is blank or cannot be parsed
   */
  @NotNull
  @UnmodifiableView
  private static List<PathPattern> compilePaths(@Nullable List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return List.of();
    }
    List<PathPattern> compiled = new ArrayList<>(paths.size());
    for (String path : paths) {
      if (path == null || path.isBlank()) {
        throw new IllegalStateException(
            "Blank entry in app.request-body-limit.paths; every entry must be a non-blank path.");
      }
      try {
        compiled.add(PathPatternParser.defaultInstance.parse(path));
      } catch (PatternParseException ex) {
        throw new IllegalStateException(
            "Invalid app.request-body-limit.paths entry '"
                + path
                + "'; PathPattern allows ** only as the final segment. Reason: "
                + ex.getMessage(),
            ex);
      }
    }
    return Collections.unmodifiableList(compiled);
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    long max = properties.maxBytes();
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
   * Skips requests the cap does not apply to: filter disabled, path not configured, no body ({@code
   * GET}/{@code HEAD}/{@code DELETE}), or a multipart upload.
   *
   * @param request the current request
   * @return {@code true} to bypass the filter
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    if (!properties.enabled() || !isCappedPath(request)) {
      return true;
    }
    String contentType = request.getContentType();
    return contentType != null
        && contentType.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
  }

  /**
   * Whether the request's <em>decoded</em> path is one of the capped ones.
   *
   * @param request the current request
   * @return {@code true} when a configured pattern matches, i.e. when the cap applies
   */
  private boolean isCappedPath(HttpServletRequest request) {
    if (cappedPaths.isEmpty()) {
      return false;
    }
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    return cappedPaths.stream().anyMatch(pattern -> pattern.matches(path));
  }

  /**
   * Writes the 413 {@code application/problem+json} response and increments {@code
   * basetool_request_body_rejected_total} (REQ-OBS-011).
   *
   * @param response the response to populate
   * @param request the rejected request; its URI becomes the problem {@code instance}
   * @throws IOException if writing the body fails
   */
  private void reject(@NotNull HttpServletResponse response, @NotNull HttpServletRequest request)
      throws IOException {
    meterRegistry.counter(MetricNames.REQUEST_BODY_REJECTED).increment();
    String correlationId = UUID.randomUUID().toString();
    log.debug(
        "Request body rejected: body exceeds the {}-byte cap on {}, correlationId={}",
        properties.maxBytes(),
        request.getRequestURI(),
        correlationId);
    String body =
        "{\"type\":\""
            + problemProperties.baseUri()
            + "request-body-too-large\",\"title\":\"Payload Too Large\",\"status\":413,"
            + "\"detail\":\"The request body exceeds the allowed size for this endpoint.\","
            + "\"code\":\"REQUEST_BODY_TOO_LARGE\",\"instance\":\""
            + jsonEscape(request.getRequestURI())
            + "\",\"correlationId\":\""
            + correlationId
            + "\"}";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    response.setHeader("Content-Type", "application/problem+json");
    response.setHeader("X-Correlation-Id", correlationId);
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
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

    @NotNull
    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }
  }
}
