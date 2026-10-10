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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationPreferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationPreferenceWriteRequest;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Mockito tests for {@link NotificationPreferenceWriteController} (REQ-NOTIF-027): the relay sends
 * no user id, reads only a boolean {@code muted}, and passes the backend status through.
 */
class NotificationPreferenceWriteControllerTest {

  private static final String URI = "/api/v1/notifications/preferences/{type}";

  private static NotificationPreferenceWriteController controller(BackendApiClient client) {
    return new NotificationPreferenceWriteController(new NotificationBackendClient(client));
  }

  @Test
  void setPreference_relaysTheTypeAndTheFlagWithoutAUserId() {
    BackendApiClient client = mock(BackendApiClient.class);
    NotificationPreferenceDto stored =
        new NotificationPreferenceDto("JOB_ORDER_CREATED", true, true);
    when(client.put(eq(URI), any(), eq(NotificationPreferenceDto.class), eq("JOB_ORDER_CREATED")))
        .thenReturn(stored);

    ResponseEntity<Object> response =
        controller(client).setPreference("JOB_ORDER_CREATED", Map.of("muted", true));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(stored, response.getBody());
    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    Mockito.verify(client)
        .put(eq(URI), body.capture(), eq(NotificationPreferenceDto.class), eq("JOB_ORDER_CREATED"));
    assertEquals(new NotificationPreferenceWriteRequest(true), body.getValue());
  }

  @Test
  void setPreference_aStringFlagIsNotATrueFlag() {
    BackendApiClient client = mock(BackendApiClient.class);

    controller(client).setPreference("JOB_ORDER_CREATED", Map.of("muted", "true"));

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    Mockito.verify(client)
        .put(eq(URI), body.capture(), eq(NotificationPreferenceDto.class), eq("JOB_ORDER_CREATED"));
    assertEquals(new NotificationPreferenceWriteRequest(false), body.getValue());
  }

  @Test
  void setPreference_relaysTheBackendStatusRatherThanFlatteningItTo500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.put(eq(URI), any(), eq(NotificationPreferenceDto.class), eq("X")))
        .thenThrow(new BackendServiceException("cannot be muted", new RuntimeException(), 400));

    ResponseEntity<Object> response = controller(client).setPreference("X", Map.of("muted", true));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  void setPreference_anUnexpectedFailureIs500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.put(eq(URI), any(), eq(NotificationPreferenceDto.class), eq("X")))
        .thenThrow(new IllegalStateException("boom"));

    ResponseEntity<Object> response = controller(client).setPreference("X", Map.of("muted", true));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }
}
