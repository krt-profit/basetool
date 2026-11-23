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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.CreateAuecJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Persists the aUEC creation form for both web transports; the backend's create command announces
 * the new order to the open overviews (REQ-ORDERS-056).
 */
@Component
@RequiredArgsConstructor
public class JobOrderCreation {

  private final JobOrderBackendClient jobOrderClient;

  /**
   * Creates the aUEC order and resolves the order's detail destination.
   *
   * @param form the validated order fields and exact net target
   * @return the created order's detail URL
   */
  @NotNull
  public String createAuecOrder(@NotNull CreateAuecJobOrderDto form) {
    JobOrderDto created = jobOrderClient.createAuecOrder(form);
    if (created == null || created.id() == null) {
      throw new BackendServiceException(
          "Backend returned no created aUEC order identity", null, HttpStatus.BAD_GATEWAY.value());
    }
    return postCreateTarget(created);
  }

  static @NotNull String postCreateTarget(@NotNull JobOrderDto created) {
    return "/orders/" + Objects.requireNonNull(created.id());
  }
}
