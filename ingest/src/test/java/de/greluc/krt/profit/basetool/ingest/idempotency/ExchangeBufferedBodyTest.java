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

package de.greluc.krt.profit.basetool.ingest.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeStoreProperties;
import de.greluc.krt.profit.basetool.ingest.edge.CachedBodyRequest;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeIdempotency;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises buffered-body admission through nested servlet wrappers and oversized replay outcomes.
 */
class ExchangeBufferedBodyTest {
  @Test
  void reusesTheNativeBufferAndDeliversAnOversizedUncachedAnswer() throws Exception {
    ExchangeIdempotency idempotency = mock(ExchangeIdempotency.class);
    ExchangeBudget budget = mock(ExchangeBudget.class);
    ExchangeStoreProperties properties = mock(ExchangeStoreProperties.class);
    when(properties.maxResultBytes()).thenReturn(32 * 1024);
    when(idempotency.find(anyString())).thenReturn(Optional.empty());
    when(idempotency.claim(anyString())).thenReturn(Optional.of("claim"));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(true);
    ExchangeIdempotencyFilter filter =
        new ExchangeIdempotencyFilter(
            idempotency,
            budget,
            mock(ExchangeQuotas.class),
            properties,
            mock(ExchangeRefusals.class),
            JsonMapper.builder().build(),
            TestLoggingProperties.defaults(),
            new SimpleMeterRegistry());
    MockHttpServletRequest raw =
        new MockHttpServletRequest("POST", "/exchange/v1/me/blueprints/changes");
    raw.addHeader(ExchangeIdempotencyFilter.IDEMPOTENCY_KEY, "buffered-key");
    raw.setAttribute(
        ExchangeRequestContext.ATTRIBUTE,
        new ExchangeRequestContext(
            "client", "member", "thumbprint", Set.of(), mock(ExchangeRegistry.Client.class), null));
    byte[] requestBody = "{\"ops\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    CachedBodyRequest buffered = new CachedBodyRequest(raw, requestBody);
    HttpServletRequestWrapper outer =
        new HttpServletRequestWrapper(buffered) {
          @Override
          public ServletInputStream getInputStream() throws IOException {
            throw new AssertionError("An admitted buffered request must not be materialized again");
          }
        };
    byte[] answer = new byte[32 * 1024 + 1];
    java.util.Arrays.fill(answer, (byte) 'x');
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        outer,
        response,
        (request, result) -> {
          assertThat(request).isSameAs(outer);
          assertThat(buffered.getInputStream().readAllBytes()).isEqualTo(requestBody);
          result.getOutputStream().write(answer);
        });
    assertThat(response.getContentAsByteArray()).isEqualTo(answer);
    verify(idempotency, never()).store(anyString(), any());
    verify(budget, never())
        .settle(anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any());
    verify(budget).release(anyString(), anyString(), anyString(), anyLong());
    verify(idempotency).releaseClaim(anyString(), eq("claim"));
  }
}
