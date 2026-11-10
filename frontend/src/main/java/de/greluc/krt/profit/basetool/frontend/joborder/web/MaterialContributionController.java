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
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialProvisionCorrectionDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/** Renders facts authorized by the backend's concrete order read policy. */
@Controller
@RequestMapping("/orders/{id}/claims")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MaterialContributionController {
  /** Relays the caller to the backend's canSeeJobOrder check without a local authority cache. */
  private final JobOrderBackendClient orders;

  /**
   * Renders retained facts only after the backend authorizes the concrete order read.
   *
   * @param id the concrete order checked by the backend
   * @param model the fragment model
   * @return the history fragment; a denied backend read propagates without any facts
   */
  @GetMapping(value = "/history", headers = "X-Requested-With=XMLHttpRequest")
  public String history(@PathVariable UUID id, Model model) {
    var history = orders.contributionHistory(id);
    model.addAttribute("contributionHistory", history == null ? List.of() : history);
    model.addAttribute("contributionOrderId", id);
    return "joborder/fragments/contribution-history :: history";
  }

  /**
   * Relays a deliberately submitted correction; the backend rechecks current writer grants.
   *
   * @param id the order
   * @param creditId the retained provision
   * @param correction observed versions and truthful replacement facts
   * @return refreshed facts; backend denials and conflicts retain their original status
   */
  @PostMapping("/provisions/{creditId}/correction")
  @ResponseBody
  public List<MaterialContributionHistoryDto> correct(
      @PathVariable UUID id,
      @PathVariable UUID creditId,
      @RequestBody MaterialProvisionCorrectionDto correction) {
    return orders.correctProvision(id, creditId, correction);
  }

  /**
   * Renders a correction form from current scoped versions and authoritative historical targets.
   *
   * @param id the concrete order
   * @param creditId the retained provision
   * @param model the form model
   * @return the correction editor fragment
   */
  @GetMapping(value = "/provisions/{creditId}/editor", headers = "X-Requested-With=XMLHttpRequest")
  public String editor(@PathVariable UUID id, @PathVariable UUID creditId, Model model) {
    var history = orders.contributionHistory(id);
    var contribution =
        Objects.requireNonNull(history).stream()
            .filter(
                row -> row.provisions().stream().anyMatch(credit -> credit.id().equals(creditId)))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    var credit =
        contribution.provisions().stream()
            .filter(row -> row.id().equals(creditId))
            .findFirst()
            .orElseThrow();
    if (!credit.canCorrect()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    model.addAttribute("contributionOrderId", id);
    model.addAttribute("contribution", contribution);
    model.addAttribute("provision", credit);
    model.addAttribute("attributionTargets", orders.attributionOptions(id, creditId));
    return "joborder/fragments/contribution-history :: editor";
  }

  /**
   * Renders connected stock versions only after the backend checks current Inventory grants.
   *
   * @param id the concrete order
   * @param creditId the retained provision
   * @param model the physical option model
   * @return the connected physical row options
   */
  @GetMapping(
      value = "/provisions/{creditId}/quantity-options",
      headers = "X-Requested-With=XMLHttpRequest")
  public String quantityOptions(@PathVariable UUID id, @PathVariable UUID creditId, Model model) {
    model.addAttribute("quantityOptions", orders.quantityCorrectionOptions(id, creditId));
    return "joborder/fragments/contribution-history :: quantityOptions";
  }

  /**
   * Offers matching current open promises only within authoritative writable claiming units.
   *
   * @param id the concrete readable order
   * @param materialId the material being provided
   * @param quality its physical quality
   * @param model the picker option model
   * @return the optional fresh-provision choices
   */
  @GetMapping(value = "/provision-options", headers = "X-Requested-With=XMLHttpRequest")
  public String provisionOptions(
      @PathVariable UUID id,
      @RequestParam UUID materialId,
      @RequestParam int quality,
      Model model) {
    var buckets =
        Objects.requireNonNull(orders.claimBuckets(id)).stream()
            .filter(bucket -> materialId.equals(bucket.material().id()))
            .filter(bucket ->
                bucket.qualityTier() == null || bucket.qualityTier().minQuality() <= quality)
            .toList();
    var writable =
        Objects.requireNonNull(orders.claimingOptions(id)).stream()
            .map(OrgUnitMembershipOptionDto::orgUnitId)
            .collect(Collectors.toSet());
    model.addAttribute("provisionBuckets", buckets);
    model.addAttribute("provisionWritableIds", writable);
    return "joborder/fragments/provision-options :: options";
  }
}
