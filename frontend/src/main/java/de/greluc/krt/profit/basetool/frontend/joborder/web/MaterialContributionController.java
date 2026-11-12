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
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** Renders facts authorized by the backend's concrete order read policy. */
@Controller
@UsesLayoutModel
@RequestMapping("/orders/{id}/claims")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MaterialContributionController {
  /** Reads retained contribution facts under the backend grants. */
  private final MaterialContributionBackendClient contributions;

  /**
   * Renders retained facts only after the backend authorizes the concrete order read.
   *
   * @param id the concrete order checked by the backend
   * @param model the fragment model
   * @return the history fragment; a denied backend read propagates without any facts
   */
  @GetMapping(value = "/history", headers = "X-Requested-With=XMLHttpRequest")
  public String history(@PathVariable UUID id, Model model) {
    var history = contributions.contributionHistory(id);
    List<MaterialContributionHistoryDto> retained = history == null ? List.of() : history;
    model.addAttribute("contributionHistory", retained);
    model.addAttribute(
        "provisionUnitNames",
        retained.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    contribution -> contribution.claimingOrgUnit().id(),
                    contribution -> contribution.claimingOrgUnit().name(),
                    (first, _) -> first)));
    model.addAttribute("contributionOrderId", id);
    return "joborder/fragments/contribution-history :: history";
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
    var history = contributions.contributionHistory(id);
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
    model.addAttribute("attributionTargets", contributions.attributionOptions(id, creditId));
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
    model.addAttribute("quantityOptions", contributions.quantityCorrectionOptions(id, creditId));
    return "joborder/fragments/contribution-history :: quantityOptions";
  }

  /**
   * Refreshes the attribution choices while retaining the editor's deliberate selection.
   *
   * @param id the readable order
   * @param creditId the retained provision
   * @param suppliedQuality corrected original quality, or null for attribution-only choices
   * @param model the current authoritative choices
   * @return the attribution select fragment
   */
  @GetMapping(
      value = "/provisions/{creditId}/attribution-options",
      headers = "X-Requested-With=XMLHttpRequest")
  public String attributionOptions(
      @PathVariable UUID id,
      @PathVariable UUID creditId,
      @RequestParam(required = false) @Nullable Integer suppliedQuality,
      Model model) {
    model.addAttribute(
        "attributionTargets", contributions.attributionOptions(id, creditId, suppliedQuality));
    return "joborder/fragments/contribution-history :: attributionOptions";
  }

  /**
   * Renders selectable current placements for an optional physical quality correction.
   *
   * @param id the readable order
   * @param creditId the retained provision
   * @param model permitted row labels, versions and server-known affected quantities
   * @return the current quality correction choices
   */
  @GetMapping(
      value = "/provisions/{creditId}/quality-options",
      headers = "X-Requested-With=XMLHttpRequest")
  public String qualityOptions(@PathVariable UUID id, @PathVariable UUID creditId, Model model) {
    model.addAttribute("qualityOptions", contributions.qualityCorrectionOptions(id, creditId));
    return "joborder/fragments/contribution-history :: qualityOptions";
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
    model.addAttribute("provisionTargets", contributions.provisionOptions(id, materialId, quality));
    return "joborder/fragments/provision-options :: options";
  }
}
