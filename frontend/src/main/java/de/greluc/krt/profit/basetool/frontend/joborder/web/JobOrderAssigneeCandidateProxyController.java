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
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderAssigneeCandidateDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of a job order's assignee picker (REQ-ORDERS-047): the active accounts a Logistician
 * of the order may assign, which the backend gates per order.
 */
@RestController
@RequestMapping("/orders")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class JobOrderAssigneeCandidateProxyController {

  /** Runs the candidate search on the backend. */
  private final JobOrderBackendClient jobOrderClient;

  /**
   * Forwards to {@code GET /api/v1/orders/{id}/assignee-candidates}.
   *
   * @param id the job order
   * @param query the handle fragment, or {@code null}/blank for the first accounts
   * @return the candidates, never {@code null}; a backend failure is answered by the global handler
   */
  @NotNull
  @GetMapping(value = "/{id}/assignee-candidates", headers = "X-Requested-With=XMLHttpRequest")
  public List<JobOrderAssigneeCandidateDto> assigneeCandidates(
      @PathVariable UUID id, @RequestParam(required = false) @Nullable String query) {
    List<JobOrderAssigneeCandidateDto> candidates =
        jobOrderClient.assigneeCandidates(id, query == null ? "" : query);
    return candidates != null ? candidates : List.of();
  }
}
