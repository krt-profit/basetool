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

package de.greluc.krt.profit.basetool.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import de.greluc.krt.profit.basetool.ingest.edge.CachedBodyRequest;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Measures the request fingerprint and rejected-response conversion working set on synthetic data.
 */
class ExchangeBufferAllocationTest {
  private static volatile Object retained;

  @Test
  void recordsNearCapCopyAndBufferedAllocationCosts() throws Exception {
    assumeTrue(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean);
    ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    assumeTrue(allocations.isThreadAllocatedMemorySupported());
    allocations.setThreadAllocatedMemoryEnabled(true);
    byte[] body =
        new byte
            [Math.toIntExact(
                    de.greluc.krt.profit.basetool.ingest.support.TestProperties.ingest()
                        .maxPayloadBytes())
                - 1];
    java.util.Arrays.fill(body, (byte) 'x');
    CachedBodyRequest request = new CachedBodyRequest(new MockHttpServletRequest(), body);
    ContentCachingResponseWrapper response =
        new ContentCachingResponseWrapper(new MockHttpServletResponse());
    response.getOutputStream().write(new byte[32 * 1024 + 1]);
    long thread = Thread.currentThread().threadId();
    for (int warm = 0; warm < 3; warm++) {
      copied(request, response);
      buffered(request, response);
    }
    long before = allocations.getThreadAllocatedBytes(thread);
    for (int i = 0; i < 4; i++) copied(request, response);
    long copied = (allocations.getThreadAllocatedBytes(thread) - before) / 4;
    before = allocations.getThreadAllocatedBytes(thread);
    for (int i = 0; i < 4; i++) buffered(request, response);
    long buffered = (allocations.getThreadAllocatedBytes(thread) - before) / 4;
    assertThat(buffered).isLessThan(copied);
    System.out.printf(
        "Synthetic request=%d response=%d allocated copy=%d buffered=%d bytes/op%n",
        body.length, response.getContentSize(), copied, buffered);
  }

  private static void copied(CachedBodyRequest request, ContentCachingResponseWrapper response)
      throws Exception {
    retained =
        ExchangeIdempotency.fingerprint(
            "POST", "/changes", request.getInputStream().readAllBytes());
    retained = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private static void buffered(CachedBodyRequest request, ContentCachingResponseWrapper response) {
    retained = ExchangeIdempotency.fingerprint("POST", "/changes", request.bufferedBody());
    if (response.getContentSize() <= 32 * 1024) {
      retained = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }
  }
}
