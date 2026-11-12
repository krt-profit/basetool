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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionTemplateBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.CreateMissionTemplateRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionObjectiveKind;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateOwnerDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplatePlan;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateSummaryDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Renders the org-unit template list and its independent editor. */
@Controller
@UsesLayoutModel
@RequestMapping("/mission-templates")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MissionTemplatePageController {

  private final MissionTemplateBackendClient templates;
  private final MissionBackendClient missions;
  private final LayoutContextLoader layout;
  private final JsonMapper jsonMapper;

  /**
   * Shows an eligible unit's templates and the controls for managing them.
   *
   * @param sourceMissionId the optional mission to use as the editor's starting point
   * @param request the request carrying the active org unit
   * @param model the template view model
   * @return the management page
   */
  @GetMapping
  public String page(
      @RequestParam(required = false) @Nullable UUID sourceMissionId,
      HttpServletRequest request,
      Model model) {
    List<MissionTemplateOwnerDto> owners = withBackendStatus(templates::owners);
    UUID active = layout.load(request).activeOrgUnitId();
    UUID owner =
        owners.stream()
            .map(MissionTemplateOwnerDto::id)
            .filter(id -> id.equals(active))
            .findFirst()
            .orElseGet(() -> owners.isEmpty() ? null : owners.getFirst().id());
    if (sourceMissionId != null) {
      if (owner == null) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
      }
      MissionDto source = withBackendStatus(() -> missions.mission(sourceMissionId));
      if (source == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      model.addAttribute("templateDraft", jsonMapper.writeValueAsString(draft(owner, source)));
    }
    model.addAttribute("owners", owners);
    if (owner != null) {
      addListModel(owner, 0, model);
    }
    var ships = owner == null ? null : missions.shipTypes();
    model.addAttribute(
        "shipTypes", ships == null || ships.content() == null ? List.of() : ships.content());
    model.addAttribute("objectiveKinds", MissionObjectiveKind.values());
    return "mission/mission-templates";
  }

  /**
   * Renders the list fragment for an eligible owner without touching the editor.
   *
   * @param ownerId the selected owning unit
   * @param page the zero-based page index
   * @param model the fragment view model
   * @return the list fragment
   */
  @GetMapping("/list")
  public String list(
      @RequestParam UUID ownerId, @RequestParam(defaultValue = "0") int page, Model model) {
    addListModel(ownerId, page, model);
    return "mission/mission-templates :: list";
  }

  private void addListModel(UUID owner, int page, Model model) {
    PageResponse<MissionTemplateSummaryDto> result =
        withBackendStatus(() -> templates.list(owner, page));
    if (result != null) {
      int last = Math.max(0, result.totalPages() - 1);
      if (result.page() > last) {
        result = withBackendStatus(() -> templates.list(owner, last));
      }
    }
    model.addAttribute("ownerId", owner);
    model.addAttribute("templatePage", result);
    model.addAttribute("templates", PageResponse.contentOf(result));
  }

  /**
   * Copies the reusable fields from the caller's normal mission detail response.
   *
   * @param owner a currently manageable destination
   * @param source the visible mission detail
   * @return the independent draft
   */
  private static CreateMissionTemplateRequest draft(UUID owner, MissionDto source) {
    return new CreateMissionTemplateRequest(
        owner,
        source.name(),
        Stream.ofNullable(source.objectives())
            .flatMap(List::stream)
            .map(row -> new MissionTemplatePlan.Objective(row.title(), row.kind()))
            .toList(),
        Stream.ofNullable(source.steps())
            .flatMap(List::stream)
            .map(row -> new MissionTemplatePlan.Step(row.title(), row.meta()))
            .toList(),
        Stream.ofNullable(source.assignedUnits())
            .flatMap(List::stream)
            .map(row ->
                new MissionTemplatePlan.Unit(
                    row.name(),
                    row.shipType() == null ? null : row.shipType().id(),
                    row.note(),
                    Boolean.TRUE.equals(row.highValueUnit()),
                    row.frequency()))
            .toList());
  }
}
