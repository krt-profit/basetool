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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers the request wrapper the size filter installs for a chunked ({@code Content-Length: -1})
 * body: the controller must be able to read that body again, through either the byte stream or the
 * reader, exactly as it was sent. The wrapper is what makes the INGEST-DOS-1 streaming guard
 * transparent to Spring MVC — if it mis-serves the body, every chunked ingest silently 400s on a
 * truncated payload.
 */
class CachedBodyRequestTest {

  private static final String BODY = "{\"schemaVersion\":1,\"orders\":[]}";

  /** Runs the size filter over a chunked request and hands back the wrapper it created. */
  private static ServletRequest wrappedChunkedRequest() throws Exception {
    IngestProperties properties = new IngestProperties();
    properties.setMaxPayloadBytes(1024);
    PayloadSizeLimitFilter filter =
        new PayloadSizeLimitFilter(
            properties, JsonMapper.builder().build(), new SimpleMeterRegistry());

    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/v1/refinery-extract") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };
    request.setContent(BODY.getBytes(StandardCharsets.UTF_8));

    AtomicReference<ServletRequest> captured = new AtomicReference<>();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(ServletRequest req, jakarta.servlet.ServletResponse res) {
            captured.set(req);
          }
        };
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return captured.get();
  }

  @Test
  void replaysTheBufferedBodyThroughTheInputStream() throws Exception {
    ServletInputStream in = wrappedChunkedRequest().getInputStream();

    assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(BODY);
  }

  @Test
  void replaysTheBufferedBodyThroughTheReader() throws Exception {
    try (BufferedReader reader = wrappedChunkedRequest().getReader()) {
      assertThat(reader.readLine()).isEqualTo(BODY);
    }
  }

  @Test
  void reportsReadyImmediatelyAndFinishedOnlyOnceDrained() throws Exception {
    ServletInputStream in = wrappedChunkedRequest().getInputStream();

    assertThat(in.isReady()).isTrue();
    assertThat(in.isFinished()).isFalse();
    in.readAllBytes();
    assertThat(in.isFinished()).isTrue();
  }

  @Test
  void refusesAsyncReadsBecauseTheBodyIsAlreadyInMemory() throws Exception {
    ServletInputStream in = wrappedChunkedRequest().getInputStream();

    assertThatThrownBy(() -> in.setReadListener(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void servesEachInputStreamFromTheStartSoTheBodyCanBeReadTwice() throws Exception {
    ServletRequest wrapped = wrappedChunkedRequest();

    assertThat(new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo(BODY);
    assertThat(new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo(BODY);
  }
}
