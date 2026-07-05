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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link SpecialCommandAdminProxyController}. The controller forwards the per-SK
 * profit-eligibility toggle to the backend. {@code isProfitEligible} is carried on the cached
 * org-units owner-picker options and the admin switcher's SK catalogue, so the contract under test
 * (REQ-DATA-007) mirrors {@code SquadronAdminProxyController}: the toggle forwards the PATCH and
 * then evicts {@code STATIC_DATA_CACHE} — in that order — so no cached surface serves a stale flag
 * up to the cache TTL.
 */
@ExtendWith(MockitoExtension.class)
class SpecialCommandAdminProxyControllerTest {

  @Mock private BackendApiClient backendApiClient;

  @InjectMocks private SpecialCommandAdminProxyController controller;

  @Test
  void setProfitEligible_forwardsPatch_thenEvictsStaticDataCache() {
    // Given
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("eligible", true);

    // When
    ResponseEntity<Void> response = controller.setProfitEligible(id, body);

    // Then — 204. Eviction runs AFTER the write so the cleared cache repopulates from the
    // already-mutated backend state, never the reverse.
    assertEquals(204, response.getStatusCode().value());
    InOrder inOrder = inOrder(backendApiClient);
    inOrder
        .verify(backendApiClient)
        .patch(eq("/api/v1/special-commands/" + id + "/profit-eligible"), eq(body), eq(Void.class));
    inOrder.verify(backendApiClient).clearStaticDataCache();
    inOrder.verifyNoMoreInteractions();
  }
}
