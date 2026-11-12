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
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionTemplateBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionObjectiveKind;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateOwnerDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

  /**
   * Shows an eligible unit's templates and the controls for managing them.
   *
   * @param request the request carrying the active org unit
   * @param model the template view model
   * @return the management page
   */
  @GetMapping
  public String page(HttpServletRequest request, Model model) {
    List<MissionTemplateOwnerDto> owners = withBackendStatus(templates::owners);
    UUID active = layout.load(request).activeOrgUnitId();
    UUID owner =
        owners.stream()
            .map(MissionTemplateOwnerDto::id)
            .filter(id -> id.equals(active))
            .findFirst()
            .orElseGet(() -> owners.isEmpty() ? null : owners.getFirst().id());
    model.addAttribute("owners", owners);
    model.addAttribute("ownerId", owner);
    model.addAttribute(
        "templates", owner == null ? List.of() : withBackendStatus(() -> templates.list(owner)));
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
   * @param model the fragment view model
   * @return the list fragment
   */
  @GetMapping("/list")
  public String list(@RequestParam UUID ownerId, Model model) {
    model.addAttribute("templates", withBackendStatus(() -> templates.list(ownerId)));
    return "mission/mission-templates :: list";
  }
}
