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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class BankReportProxyControllerTest {

  @Mock private WebClient webClient;
  @Mock private WebClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
  @Mock private WebClient.RequestHeadersSpec<?> requestHeadersSpec;
  @Mock private WebClient.ResponseSpec responseSpec;

  @InjectMocks private BankReportProxyController controller;

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void downloadStatement_shouldReturnPdfBytes_andForwardPeriodToBackendUri() {
    // Given
    UUID id = UUID.randomUUID();
    byte[] fakePdf = new byte[] {0x25, 0x50, 0x44, 0x46};
    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);

    doReturn(requestHeadersUriSpec).when(webClient).get();
    doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(anyString());
    doReturn(requestHeadersSpec).when(requestHeadersSpec).headers(any(Consumer.class));
    doReturn(responseSpec).when(requestHeadersSpec).retrieve();
    doReturn(Mono.just(fakePdf)).when(responseSpec).bodyToMono(byte[].class);

    // When
    ResponseEntity<byte[]> response =
        controller.downloadStatement(
            id,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-02-01T00:00:00Z"),
            "Europe/Berlin");

    // Then
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertArrayEquals(fakePdf, response.getBody());
    assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
    String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    assertNotNull(disposition);
    assertTrue(disposition.contains("attachment"));
    assertTrue(disposition.contains("kontoauszug-" + id + ".pdf"));
    org.mockito.Mockito.verify(requestHeadersUriSpec).uri(uriCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("/api/v1/bank/accounts/" + id + "/statement"));
    assertTrue(uriCaptor.getValue().contains("from=2026-01-01T00:00:00Z"));
    assertTrue(uriCaptor.getValue().contains("to=2026-02-01T00:00:00Z"));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void downloadStatement_shouldPropagateBackendStatus() {
    // Given
    UUID id = UUID.randomUUID();
    doReturn(requestHeadersUriSpec).when(webClient).get();
    doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(anyString());
    doReturn(requestHeadersSpec).when(requestHeadersSpec).headers(any(Consumer.class));
    doReturn(responseSpec).when(requestHeadersSpec).retrieve();
    doReturn(Mono.error(WebClientResponseException.create(403, "Forbidden", null, null, null)))
        .when(responseSpec)
        .bodyToMono(byte[].class);

    // When & Then
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.downloadStatement(
                    id,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-02-01T00:00:00Z"),
                    null));
    assertEquals(403, ex.getStatusCode().value());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void downloadThreeMonthReport_shouldReturnPdfBytes() {
    // Given
    byte[] fakePdf = new byte[] {0x25, 0x50, 0x44, 0x46};
    doReturn(requestHeadersUriSpec).when(webClient).get();
    doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(anyString());
    doReturn(requestHeadersSpec).when(requestHeadersSpec).headers(any(Consumer.class));
    doReturn(responseSpec).when(requestHeadersSpec).retrieve();
    doReturn(Mono.just(fakePdf)).when(responseSpec).bodyToMono(byte[].class);

    // When
    ResponseEntity<byte[]> response = controller.downloadThreeMonthReport(null);

    // Then
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertArrayEquals(fakePdf, response.getBody());
    String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    assertNotNull(disposition);
    assertTrue(disposition.contains("bank-3-monats-report.pdf"));
  }
}
