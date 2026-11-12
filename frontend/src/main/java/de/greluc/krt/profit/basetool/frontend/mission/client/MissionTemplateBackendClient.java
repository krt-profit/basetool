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

package de.greluc.krt.profit.basetool.frontend.mission.client;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.CreateMissionTemplateRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateOwnerDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateSummaryDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateMissionTemplateRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Reads and writes mission templates through the backend's unit-scoped API. */
@Service
@RequiredArgsConstructor
public class MissionTemplateBackendClient {

  private static final String TEMPLATES = "/api/v1/mission-templates";
  private static final String TEMPLATE = TEMPLATES + "/{id}";
  private static final String CHOICES = "/api/v1/missions/template-choices";
  private static final String PREVIEW = "/api/v1/missions/template-preview/{id}";
  private static final String OWNER_PARAM = "?owningOrgUnitId={ownerId}";
  private static final ParameterizedTypeReference<List<MissionTemplateSummaryDto>> SUMMARIES =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<List<MissionTemplateOwnerDto>> OWNERS =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backend;

  /**
   * Lists templates applicable to the effective owner of a new mission.
   *
   * @param ownerId the optional picked owner
   * @return the applicable templates
   */
  public List<MissionTemplateSummaryDto> choices(@Nullable UUID ownerId) {
    return ownerId == null
        ? backend.get(CHOICES, SUMMARIES)
        : backend.get(CHOICES + OWNER_PARAM, SUMMARIES, ownerId);
  }

  /**
   * Reads the applicable template and the revision used by the create form.
   *
   * @param id the template id
   * @param ownerId the optional picked owner
   * @return the scoped preview
   */
  public MissionTemplateDto preview(UUID id, @Nullable UUID ownerId) {
    return ownerId == null
        ? backend.get(PREVIEW, MissionTemplateDto.class, id)
        : backend.get(PREVIEW + OWNER_PARAM, MissionTemplateDto.class, id, ownerId);
  }

  /**
   * Reads the owning units the caller may manage in the active context.
   *
   * @return the eligible units
   */
  public List<MissionTemplateOwnerDto> owners() {
    return backend.get(TEMPLATES + "/owning-org-units", OWNERS);
  }

  /**
   * Lists templates for an eligible owner.
   *
   * @param ownerId the owning unit
   * @return the template summaries
   */
  public List<MissionTemplateSummaryDto> list(UUID ownerId) {
    return backend.get(TEMPLATES + "?owningOrgUnitId={ownerId}", SUMMARIES, ownerId);
  }

  /**
   * Loads the current planning content for editing.
   *
   * @param id the template id
   * @return the editable template
   */
  public MissionTemplateDto detail(UUID id) {
    return backend.get(TEMPLATE, MissionTemplateDto.class, id);
  }

  /**
   * Creates a new template for the selected owner.
   *
   * @param body the owner and initial planning content
   * @return the persisted template and revision
   */
  public MissionTemplateDto create(CreateMissionTemplateRequest body) {
    return backend.post(TEMPLATES, body, MissionTemplateDto.class);
  }

  /**
   * Replaces the editable planning content at the supplied revision.
   *
   * @param id the template id
   * @param body the planning content and expected revision
   * @return the saved template and current revision
   */
  public MissionTemplateDto update(UUID id, UpdateMissionTemplateRequest body) {
    return backend.put(TEMPLATE, body, MissionTemplateDto.class, id);
  }

  /**
   * Deletes the template at the supplied revision.
   *
   * @param id the template id
   * @param version the expected revision
   */
  public void delete(UUID id, long version) {
    backend.delete(TEMPLATE + "?version={version}", Void.class, id, version);
  }
}
