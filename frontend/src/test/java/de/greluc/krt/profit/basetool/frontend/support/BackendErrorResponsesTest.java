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

package de.greluc.krt.profit.basetool.frontend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link BackendErrorResponses}, pinning both the {@code problem+json} relay shape
 * shared by the AJAX page controllers whose private {@code propagateBackendError} copies it
 * replaced and the {@link BackendErrorResponses#relay(Logger, String,
 * BackendErrorResponses.BackendCall)} try/catch wrapper's success / {@code BackendServiceException}
 * / generic-exception branches (including their DEBUG-vs-ERROR log level contract).
 */
class BackendErrorResponsesTest {

  @Test
  @SuppressWarnings("unchecked")
  void propagate_relaysStatusCodeDetailAndCorrelationId() {
    BackendServiceException e =
        new BackendServiceException(
            "conflict", null, 409, "OPTIMISTIC_LOCK", "corr-1", List.of(), "row changed");

    ResponseEntity<Object> response = BackendErrorResponses.propagateBackendError(e);

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body)
        .containsEntry("status", 409)
        .containsEntry("code", "OPTIMISTIC_LOCK")
        .containsEntry("detail", "row changed")
        .containsEntry("correlationId", "corr-1");
  }

  @Test
  @SuppressWarnings("unchecked")
  void propagate_omitsBlankDetailAndCorrelationId() {
    BackendServiceException e =
        new BackendServiceException("boom", null, 500, "UNKNOWN", "", List.of(), null);

    ResponseEntity<Object> response = BackendErrorResponses.propagateBackendError(e);

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsOnlyKeys("status", "code");
  }

  @Test
  void relay_returnsActionResponseOnSuccessWithoutLogging() {
    Logger log = mock(Logger.class);

    ResponseEntity<Object> ok = ResponseEntity.ok("payload");
    ResponseEntity<Object> response = BackendErrorResponses.relay(log, "do thing", () -> ok);

    assertThat(response).isSameAs(ok);
    verifyNoInteractions(log);
  }

  @Test
  @SuppressWarnings("unchecked")
  void relay_propagatesBackendServiceExceptionAsProblemJsonAtDebug() {
    Logger log = mock(Logger.class);
    BackendServiceException e =
        new BackendServiceException(
            "conflict", null, 409, "OPTIMISTIC_LOCK", "corr-9", List.of(), "row changed");

    ResponseEntity<Object> response =
        BackendErrorResponses.relay(
            log,
            "update thing",
            () -> {
              throw e;
            });

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body)
        .containsEntry("code", "OPTIMISTIC_LOCK")
        .containsEntry("correlationId", "corr-9");
    verify(log)
        .debug(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.eq("update thing"),
            ArgumentMatchers.eq(HttpStatus.CONFLICT.value()),
            ArgumentMatchers.eq("conflict"));
  }

  @Test
  void relay_mapsGenericExceptionToEmpty500AtError() {
    Logger log = mock(Logger.class);
    RuntimeException boom = new IllegalStateException("boom");

    ResponseEntity<Object> response =
        BackendErrorResponses.relay(
            log,
            "risky thing",
            () -> {
              throw boom;
            });

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody()).isNull();
    verify(log)
        .error(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.eq("risky thing"),
            ArgumentMatchers.eq(boom));
  }
}
