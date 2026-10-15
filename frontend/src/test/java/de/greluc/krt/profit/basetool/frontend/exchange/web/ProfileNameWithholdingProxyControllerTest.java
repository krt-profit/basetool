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

package de.greluc.krt.profit.basetool.frontend.exchange.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.exchange.client.ExchangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.exchange.model.NameWithholdingRequest;
import de.greluc.krt.profit.basetool.frontend.exchange.model.NameWithholdingResponse;
import de.greluc.krt.profit.basetool.frontend.exchange.model.ProfileNameWithholdingForm;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/** Unit tests of {@link ProfileNameWithholdingProxyController} (REQ-XCH-044). */
@ExtendWith(MockitoExtension.class)
class ProfileNameWithholdingProxyControllerTest {

  private static final String PATH = "/api/v1/connected-apps/name-withholding";

  @Mock private BackendApiClient backendApiClient;

  private ProfileNameWithholdingProxyController controller;

  @BeforeEach
  void setUp() {
    controller =
        new ProfileNameWithholdingProxyController(new ExchangeBackendClient(backendApiClient));
  }

  @Test
  void theSavedChoiceAndItsNewVersionAreReturned() {
    when(backendApiClient.put(
            PATH, new NameWithholdingRequest(true, 0L), NameWithholdingResponse.class))
        .thenReturn(new NameWithholdingResponse(true, 1L));

    ResponseEntity<Object> response =
        controller.updateNameWithholding(new ProfileNameWithholdingForm(true, null));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo(new NameWithholdingResponse(true, 1L));
  }

  @Test
  void aBackendConflictIsRelayedWithItsStatus() {
    doThrow(
            new BackendServiceException(
                "concurrency-conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), null))
        .when(backendApiClient)
        .put(eq(PATH), any(), eq(NameWithholdingResponse.class));

    ResponseEntity<Object> response =
        controller.updateNameWithholding(new ProfileNameWithholdingForm(false, 4L));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
  }
}
