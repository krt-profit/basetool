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

import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.mission.internal.CreateMissionTemplateRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionTemplateDto;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionTemplateOwnerDto;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionTemplateService;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionTemplateSummaryDto;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateMissionTemplateRequest;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST API for scoped mission template management. */
@RestController
@RequestMapping("/api/v1/mission-templates")
@RequiredArgsConstructor
@Tag(name = "Mission templates")
@PreAuthorize("isAuthenticated()")
public class MissionTemplateController {

  private final MissionTemplateService templates;

  /**
   * Lists templates in one manageable org unit.
   *
   * @param owningOrgUnitId the requested unit
   * @param page the zero-based page index
   * @param size the requested page size
   * @return the unit's page of template summaries
   */
  @GetMapping
  @Operation(
      operationId = "listMissionTemplates",
      summary = "List mission templates in one owning org unit",
      description = "Returns template summaries ordered by name and id.")
  public PageResponse<MissionTemplateSummaryDto> list(
      @RequestParam UUID owningOrgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return PageResponse.of(
        templates.list(owningOrgUnitId, PaginationUtil.createUnsortedPageRequest(page, size)));
  }

  /**
   * Saves a new template under the selected org-unit owner.
   *
   * @param request the destination and reusable content
   * @return the stored template with its editor revision
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "createMissionTemplate",
      summary = "Create a mission template",
      description =
          "A duplicate name in the owning org unit returns 409 DUPLICATE_ENTITY with a"
              + " fieldErrors entry for name.")
  public MissionTemplateDto create(@Valid @RequestBody CreateMissionTemplateRequest request) {
    return templates.create(request);
  }

  /**
   * Reads a template for editing.
   *
   * @param id the template id
   * @return the saved template and editor revision
   */
  @GetMapping("/{id}")
  @Operation(operationId = "readMissionTemplate", summary = "Read a mission template")
  public MissionTemplateDto get(@PathVariable UUID id) {
    return templates.get(id);
  }

  /**
   * Replaces the plan at the supplied revision.
   *
   * @param id the template id
   * @param request the current revision and replacement content
   * @return the saved template and editor revision
   */
  @PutMapping("/{id}")
  @Operation(
      operationId = "updateMissionTemplate",
      summary = "Update a mission template",
      description =
          "A stale revision returns 409 OPTIMISTIC_LOCK. A duplicate name in the owning org unit"
              + " returns 409 DUPLICATE_ENTITY with a fieldErrors entry for name.")
  public MissionTemplateDto update(
      @PathVariable UUID id, @Valid @RequestBody UpdateMissionTemplateRequest request) {
    return templates.update(id, request);
  }

  /**
   * Removes a template at the supplied revision.
   *
   * @param id the template id
   * @param version the expected revision
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      operationId = "deleteMissionTemplate",
      summary = "Delete a mission template",
      description = "A stale revision returns 409 OPTIMISTIC_LOCK.")
  public void delete(@PathVariable UUID id, @RequestParam long version) {
    templates.delete(id, version);
  }

  /**
   * Lists active units where the caller may manage templates.
   *
   * @return selectable template owners
   */
  @GetMapping("/owning-org-units")
  @Operation(
      operationId = "listMissionTemplateOwners",
      summary = "List selectable mission template owners")
  public List<MissionTemplateOwnerDto> owners() {
    return templates.owners();
  }
}
