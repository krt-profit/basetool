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

package de.greluc.krt.profit.basetool.frontend.orgunit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.orgunit.client.OrgUnitBackendClient;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SpecialCommandProfitEligibleToggleRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link SpecialCommandAdminProxyController}: the profit-eligibility toggle forwards
 * the PATCH and then evicts the {@code SQUADRON} and {@code ORG_UNIT} caches, in that order
 * (REQ-DATA-007).
 */
@ExtendWith(MockitoExtension.class)
class SpecialCommandAdminProxyControllerTest {

  @Mock private BackendApiClient backendApiClient;

  private SpecialCommandAdminProxyController controller;

  @BeforeEach
  void setUp() {
    controller =
        new SpecialCommandAdminProxyController(
            new OrgUnitBackendClient(backendApiClient),
            new CatalogueCacheEviction(backendApiClient));
  }

  @Test
  void setProfitEligible_forwardsPatch_thenEvictsStaticDataCache() {
    UUID id = UUID.fromString("7d1e5c2a-3b4f-4a6e-8c9d-1f2a3b4c5d6e");
    SpecialCommandProfitEligibleToggleRequest body =
        new SpecialCommandProfitEligibleToggleRequest(true);

    ResponseEntity<Void> response = controller.setProfitEligible(id, body);

    assertEquals(204, response.getStatusCode().value());
    InOrder inOrder = inOrder(backendApiClient);
    inOrder
        .verify(backendApiClient)
        .patch(
            eq("/api/v1/special-commands/{id}/profit-eligible"), eq(body), eq(Void.class), eq(id));
    inOrder.verify(backendApiClient).evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
    inOrder.verifyNoMoreInteractions();
  }
}
