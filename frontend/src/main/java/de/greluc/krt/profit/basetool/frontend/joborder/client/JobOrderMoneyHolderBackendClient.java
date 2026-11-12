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

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyHolderCandidate;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyHolderIdentityDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Relays explicit holder identity maintenance to the owning order API. */
@Service
@RequiredArgsConstructor
public class JobOrderMoneyHolderBackendClient {

  private static final String HOLDERS = "/api/v1/orders/{id}/money/holders";
  private final BackendApiClient backend;

  /**
   * Applies a deliberate holder name, link or merge correction.
   *
   * @param id the order
   * @param holder the existing holder
   * @param request the chosen correction and observed revisions
   */
  public void change(UUID id, UUID holder, JobOrderMoneyHolderIdentityDto request) {
    backend.patch(HOLDERS + "/{holder}/identity", request, Void.class, id, holder);
  }

  /**
   * Searches the active accounts the responsible writer can explicitly select.
   *
   * @param id the order
   * @param query the search text
   * @return available account references
   */
  public List<JobOrderMoneyHolderCandidate> candidates(UUID id, String query) {
    return backend.get(
        HOLDERS + "/identity-candidates?q={query}",
        new ParameterizedTypeReference<>() {},
        id,
        query);
  }
}
