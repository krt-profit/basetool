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

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderCreateUnitOptionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderForm;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemForm;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderPrefill;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Renders the authorized creation form with the backend's current choices and proposed pair. */
@Controller
@UsesLayoutModel
@RequestMapping("/orders")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class JobOrderCreatePageController {

  private final JobOrderPageReader pageReader;
  private final QualityTierCatalog qualityTierCatalog;

  /**
   * Renders the create-order form ({@code /orders/create}) with the material and orderable-item
   * catalogs and both owner-pickers (ADR-0149).
   *
   * @param source optional origin marker carried by the form
   * @param prefill optional material or item line and requesting unit to start the form with
   * @param model Thymeleaf model populated with form and reference catalogs
   * @return the {@code orders-create} view name
   */
  @NotNull
  @GetMapping("/create")
  public String viewCreateForm(
      @RequestParam(required = false) String source, JobOrderPrefill prefill, Model model) {
    if (!model.containsAttribute("jobOrderForm")) {
      JobOrderForm form = new JobOrderForm();
      form.setSource(source);
      prefill.applyTo(form);
      model.addAttribute("jobOrderForm", form);
    } else {
      JobOrderForm form = (JobOrderForm) model.getAttribute("jobOrderForm");
      if (form != null && form.getSource() == null) {
        form.setSource(source);
      }
    }
    if (!model.containsAttribute("jobOrderItemForm")) {
      JobOrderItemForm itemForm = new JobOrderItemForm();
      itemForm.setSource(source);
      prefill.applyTo(itemForm);
      model.addAttribute("jobOrderItemForm", itemForm);
    }
    if (prefill.itemOrder()) {
      model.addAttribute("startInItemMode", true);
      model.addAttribute("editItems", List.of(prefill.itemLine()));
    }
    JobOrderPageReader.OrderCreatePage create = pageReader.readOrderCreate();
    model.addAttribute("materials", create.materials());
    model.addAttribute("hasOrderableItems", create.hasOrderableItems());
    model.addAttribute("squadrons", create.squadrons());
    model.addAttribute("qualityTiers", qualityTierCatalog.all());
    JobOrderCreateOptionsDto choices = create.options();
    model.addAttribute("requestingOptions", creationMembershipOptions(choices.requestingOptions()));
    model.addAttribute(
        "responsibleOptions", creationMembershipOptions(choices.responsibleOptions()));
    model.addAttribute(
        "creationWritableUnitIds",
        java.util.stream.Stream.concat(
                choices.responsibleOptions().stream(), choices.requestingOptions().stream())
            .filter(JobOrderCreateUnitOptionDto::canWrite)
            .map(option -> option.orgUnitId().toString())
            .distinct()
            .collect(Collectors.joining(",")));
    JobOrderForm materialForm = (JobOrderForm) model.getAttribute("jobOrderForm");
    if (materialForm != null) {
      materialForm.setResponsibleOrgUnitId(
          creationSelection(
              materialForm.getResponsibleOrgUnitId(),
              choices.responsibleOptions(),
              choices.suggestedResponsibleOrgUnitId()));
      materialForm.setRequestingOrgUnitId(
          creationSelection(
              materialForm.getRequestingOrgUnitId(),
              choices.requestingOptions(),
              choices.suggestedRequestingOrgUnitId()));
    }
    JobOrderItemForm itemForm = (JobOrderItemForm) model.getAttribute("jobOrderItemForm");
    if (itemForm != null) {
      itemForm.setResponsibleOrgUnitId(
          creationSelection(
              itemForm.getResponsibleOrgUnitId(),
              choices.responsibleOptions(),
              choices.suggestedResponsibleOrgUnitId()));
      itemForm.setRequestingOrgUnitId(
          creationSelection(
              itemForm.getRequestingOrgUnitId(),
              choices.requestingOptions(),
              choices.suggestedRequestingOrgUnitId()));
    }
    return "joborder/orders-create";
  }

  private static List<OrgUnitMembershipOptionDto> creationMembershipOptions(
      @NotNull List<JobOrderCreateUnitOptionDto> options) {
    return options.stream()
        .map(option ->
            new OrgUnitMembershipOptionDto(
                option.orgUnitId(), option.orgUnitName(), option.shorthand(), option.kind()))
        .toList();
  }

  private static @Nullable UUID creationSelection(
      @Nullable UUID selected,
      @NotNull List<JobOrderCreateUnitOptionDto> options,
      @Nullable UUID proposed) {
    return options.stream().anyMatch(option -> option.orgUnitId().equals(selected))
        ? selected
        : proposed;
  }
}
