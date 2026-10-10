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

import de.greluc.krt.profit.basetool.frontend.orgunit.client.OrgUnitBackendClient;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronProfitEligibleToggleRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronPromotionToggleRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.service.CatalogueCacheEviction;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link SquadronAdminProxyController}: each flag toggle forwards the PATCH and then
 * evicts the {@code SQUADRON} and {@code ORG_UNIT} caches, in that order (REQ-DATA-007).
 */
@ExtendWith(MockitoExtension.class)
class SquadronAdminProxyControllerTest {

  private static final UUID ID = UUID.fromString("2b8e4f1c-6d3a-4e7b-9c0f-5a1d2e3f4a5b");

  @Mock private BackendApiClient backendApiClient;

  private SquadronAdminProxyController controller;

  @BeforeEach
  void setUp() {
    controller =
        new SquadronAdminProxyController(
            new OrgUnitBackendClient(backendApiClient),
            new CatalogueCacheEviction(backendApiClient));
  }

  @Test
  void setPromotionEnabled_forwardsPatch_thenEvictsStaticDataCache() {
    SquadronPromotionToggleRequest body = new SquadronPromotionToggleRequest(true);

    ResponseEntity<Void> response = controller.setPromotionEnabled(ID, body);

    assertEquals(204, response.getStatusCode().value());
    InOrder inOrder = inOrder(backendApiClient);
    inOrder
        .verify(backendApiClient)
        .patch(eq("/api/v1/squadrons/{id}/promotion-enabled"), eq(body), eq(Void.class), eq(ID));
    inOrder.verify(backendApiClient).evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  void setProfitEligible_forwardsPatch_thenEvictsStaticDataCache() {
    SquadronProfitEligibleToggleRequest body = new SquadronProfitEligibleToggleRequest(false);

    ResponseEntity<Void> response = controller.setProfitEligible(ID, body);

    assertEquals(204, response.getStatusCode().value());
    InOrder inOrder = inOrder(backendApiClient);
    inOrder
        .verify(backendApiClient)
        .patch(eq("/api/v1/squadrons/{id}/profit-eligible"), eq(body), eq(Void.class), eq(ID));
    inOrder.verify(backendApiClient).evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
    inOrder.verifyNoMoreInteractions();
  }
}
