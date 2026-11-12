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

import de.greluc.krt.profit.basetool.frontend.joborder.client.MaterialContributionBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialProvisionCorrectionDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Relays material provision corrections through the backend's current authority checks. */
@RestController
@RequestMapping("/orders/{id}/claims")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MaterialProvisionCorrectionController {
  /** Applies retained provision corrections under current backend grants. */
  private final MaterialContributionBackendClient contributions;

  /**
   * Relays a deliberately submitted correction; the backend rechecks current writer grants.
   *
   * @param id the order
   * @param creditId the retained provision
   * @param correction observed versions and truthful replacement facts
   * @return refreshed facts; backend denials and conflicts retain their original status
   */
  @PostMapping("/provisions/{creditId}/correction")
  public List<MaterialContributionHistoryDto> correct(
      @PathVariable UUID id,
      @PathVariable UUID creditId,
      @RequestBody MaterialProvisionCorrectionDto correction) {
    return contributions.correctProvision(id, creditId, correction);
  }
}
