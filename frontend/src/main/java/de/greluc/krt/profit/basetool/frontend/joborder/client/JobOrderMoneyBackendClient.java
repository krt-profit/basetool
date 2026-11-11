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

package de.greluc.krt.profit.basetool.frontend.joborder.client;

import de.greluc.krt.profit.basetool.frontend.joborder.model.CreateAuecJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyHolderCandidate;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyOutcome;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyProvisionPreview;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyView;
import de.greluc.krt.profit.basetool.frontend.joborder.model.PreviewJobOrderMoneyProvisionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ProvideJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Reads and records exact order-local monetary facts through the backend API. */
@Service
@RequiredArgsConstructor
public class JobOrderMoneyBackendClient {

  private static final String MONEY = "/api/v1/orders/{id}/money";
  private final BackendApiClient backend;

  /**
   * Creates an aUEC order with its initial net target.
   *
   * @param request the shared creation fields and exact target
   * @return the created order
   */
  public JobOrderDto create(@NotNull CreateAuecJobOrderDto request) {
    return backend.post("/api/v1/orders/auec", request, JobOrderDto.class);
  }

  /**
   * Reads the authorized order's monetary projections.
   *
   * @param id the order
   * @return current holdings, history and granted actions
   */
  public JobOrderMoneyView view(@NotNull UUID id) {
    return backend.get(MONEY, JobOrderMoneyView.class, id);
  }

  /**
   * Submits or replays a provision under its persistent operation identity.
   *
   * @param id the order
   * @param request the submitted terms
   * @return the recorded provision
   */
  public JobOrderMoneyOutcome provide(@NotNull UUID id, @NotNull ProvideJobOrderMoneyDto request) {
    return backend.post(MONEY + "/provisions", request, JobOrderMoneyOutcome.class, id);
  }

  /**
   * Projects a provision against its proposed terms and holder revision.
   *
   * @param id the order
   * @param request the proposed exact terms
   * @return the amount, fee and resulting holding
   */
  public JobOrderMoneyProvisionPreview preview(
      @NotNull UUID id, @NotNull PreviewJobOrderMoneyProvisionDto request) {
    return backend.post(
        MONEY + "/provisions/preview", request, JobOrderMoneyProvisionPreview.class, id);
  }

  /**
   * Finds active accounts the current responsible writer may select.
   *
   * @param id the order
   * @param query the typed name fragment
   * @return selectable account identities and labels
   */
  public List<JobOrderMoneyHolderCandidate> holderCandidates(
      @NotNull UUID id, @NotNull String query) {
    return backend.get(
        MONEY + "/holder-candidates?q={query}", new ParameterizedTypeReference<>() {}, id, query);
  }

  /**
   * Reads an outcome under the order's current access policy.
   *
   * @param id the order
   * @param operation the submitted identity
   * @return the recorded provision
   */
  public JobOrderMoneyOutcome outcome(@NotNull UUID id, @NotNull UUID operation) {
    return backend.get(
        MONEY + "/operations/{operation}", JobOrderMoneyOutcome.class, id, operation);
  }
}
