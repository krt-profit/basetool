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

package de.greluc.krt.profit.basetool.backend.mission.web;

import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionTemplateDto;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionTemplateSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scoped template reads for the mission create form. */
@RestController
@RequestMapping("/api/v1/missions")
@RequiredArgsConstructor
@Tag(name = "Missions")
@PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
class MissionTemplateApplicationController {

  private final MissionService missions;

  /**
   * Lists templates for the effective mission owner.
   *
   * @param owningOrgUnitId the optional owner pick
   * @return the available templates
   */
  @GetMapping("/template-choices")
  @Operation(
      operationId = "listMissionTemplateChoices",
      summary = "List templates for mission creation")
  public List<MissionTemplateSummaryDto> listChoices(
      @RequestParam(required = false) @Nullable UUID owningOrgUnitId) {
    return missions.templateChoices(owningOrgUnitId);
  }

  /**
   * Previews a template for the effective mission owner.
   *
   * @param templateId the selected template
   * @param owningOrgUnitId the optional owner pick
   * @return the scoped plan and revision
   */
  @GetMapping("/template-preview/{templateId}")
  @Operation(
      operationId = "previewMissionTemplate",
      summary = "Preview a template for mission creation")
  @ApiResponse(responseCode = "200", description = "Template preview", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "404", description = "No template in the effective owning unit")
  public MissionTemplateDto preview(
      @PathVariable UUID templateId,
      @RequestParam(required = false) @Nullable UUID owningOrgUnitId) {
    return missions.templatePreview(templateId, owningOrgUnitId);
  }
}
