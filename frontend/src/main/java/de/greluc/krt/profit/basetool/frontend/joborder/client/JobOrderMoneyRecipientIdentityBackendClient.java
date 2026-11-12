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

import de.greluc.krt.profit.basetool.frontend.joborder.model.DesignateJobOrderMoneyRecipientDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyHolderCandidate;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMoneyRecipientView;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Uses the order API to correct a deliberate direct requester designation. */
@Service
@RequiredArgsConstructor
public class JobOrderMoneyRecipientIdentityBackendClient {

  private static final String RECIPIENT = "/api/v1/orders/{id}/money/recipients/{recipient}";
  private final BackendApiClient backend;

  /**
   * Corrects the person the original designation should identify.
   *
   * @param id the order
   * @param recipient the original designation
   * @param request the selected account or accountless name
   * @return the current designation
   */
  public JobOrderMoneyRecipientView correct(
      UUID id, UUID recipient, DesignateJobOrderMoneyRecipientDto request) {
    return backend.patch(
        RECIPIENT + "/identity", request, JobOrderMoneyRecipientView.class, id, recipient);
  }

  /**
   * Offers bounded active-account choices for the correction.
   *
   * @param id the order
   * @param recipient the original designation
   * @param query the search fragment
   * @return eligible accounts
   */
  public List<JobOrderMoneyHolderCandidate> candidates(UUID id, UUID recipient, String query) {
    return backend.get(
        RECIPIENT + "/identity-candidates?q={query}",
        new ParameterizedTypeReference<>() {},
        id,
        recipient,
        query);
  }
}
