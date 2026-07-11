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
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
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
 * Unit tests for {@link SquadronAdminProxyController}. The controller is a thin admin-only proxy
 * that forwards two per-squadron flag toggles to the backend. Both flags live on the cached {@code
 * SquadronDto} that {@code OrgUnitContextAdvice} reads on every authenticated render, so the
 * contract under test (REQ-DATA-007) is: each toggle forwards the PATCH and then evicts {@code
 * STATIC_DATA_CACHE} — in that order — so the shared squadron catalogue cannot serve a stale flag
 * up to the cache TTL after the toggle. Without the eviction the sidebar/title promotion gate would
 * lag behind the admin's change.
 */
@ExtendWith(MockitoExtension.class)
class SquadronAdminProxyControllerTest {

  @Mock private BackendApiClient backendApiClient;

  @InjectMocks private SquadronAdminProxyController controller;

  @Test
  void setPromotionEnabled_forwardsPatch_thenEvictsStaticDataCache() {
    // Given
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("enabled", true);

    // When
    ResponseEntity<Void> response = controller.setPromotionEnabled(id, body);

    // Then — 204. Eviction runs AFTER the write so the cleared cache repopulates from the
    // already-mutated backend state, never the reverse (which would re-cache stale data).
    assertEquals(204, response.getStatusCode().value());
    InOrder inOrder = inOrder(backendApiClient);
    inOrder
        .verify(backendApiClient)
        .patch(eq("/api/v1/squadrons/" + id + "/promotion-enabled"), eq(body), eq(Void.class));
    inOrder.verify(backendApiClient).evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  void setProfitEligible_forwardsPatch_thenEvictsStaticDataCache() {
    // Given
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("eligible", false);

    // When
    ResponseEntity<Void> response = controller.setProfitEligible(id, body);

    // Then
    assertEquals(204, response.getStatusCode().value());
    InOrder inOrder = inOrder(backendApiClient);
    inOrder
        .verify(backendApiClient)
        .patch(eq("/api/v1/squadrons/" + id + "/profit-eligible"), eq(body), eq(Void.class));
    inOrder.verify(backendApiClient).evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
    inOrder.verifyNoMoreInteractions();
  }
}
