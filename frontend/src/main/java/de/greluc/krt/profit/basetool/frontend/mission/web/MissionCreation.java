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

import static de.greluc.krt.profit.basetool.frontend.mission.web.MissionWriteValues.parseToInstant;

import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.CreateMissionRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionForm;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplatePlan;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Submits the validated mission create form through the shared backend path. */
@Component
@RequiredArgsConstructor
class MissionCreation {

  private final MissionBackendClient missionClient;
  private final LiveSyncLocalBus liveSyncLocalBus;
  private final JsonMapper objectMapper =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /**
   * Saves the create request and publishes the updated mission list.
   *
   * @param request the typed mission draft
   * @return the saved mission's management tab URL
   */
  String save(CreateMissionRequest request) {
    MissionDto created = missionClient.createMission(request);
    liveSyncLocalBus.publish(LiveSyncTopicClass.MISSIONS_LIST.prefix(), List.of("list"));
    return "/missions/" + created.id() + "?tab=verw";
  }

  /**
   * Converts the bound form and planning rows into the backend request.
   *
   * @param form the bound and validated form
   * @return the typed mission draft
   */
  CreateMissionRequest request(MissionForm form) {
    UUID operationId =
        form.operationId() == null || form.operationId().isBlank()
            ? null
            : UUID.fromString(form.operationId());
    List<CreateMissionRequest.NewObjective> objectives =
        parseCreateList(
            form.objectivesJson(), new TypeReference<List<CreateMissionRequest.NewObjective>>() {});
    List<CreateMissionRequest.NewStep> steps =
        parseCreateList(
            form.stepsJson(), new TypeReference<List<CreateMissionRequest.NewStep>>() {});
    List<MissionTemplatePlan.Unit> units =
        parseCreateList(form.unitsJson(), new TypeReference<List<MissionTemplatePlan.Unit>>() {});
    CreateMissionRequest.TemplateSelection selection =
        form.templateId() == null && form.templateVersion() == null
            ? null
            : new CreateMissionRequest.TemplateSelection(form.templateId(), form.templateVersion());
    return new CreateMissionRequest(
        form.name(),
        form.description(),
        form.calendarLink(),
        form.status(),
        parseToInstant(form.meetingTime()),
        parseToInstant(form.plannedStartTime()),
        parseToInstant(form.plannedEndTime()),
        form.isInternal(),
        operationId,
        form.owningOrgUnitId(),
        form.meetingPoint(),
        objectives,
        steps,
        units,
        selection);
  }

  /**
   * Parses one of the create form's JSON row carriers into a nested create-request list.
   *
   * @param json the hidden carrier's raw JSON, or {@code null}/blank when the section is empty
   * @param typeRef the target list element type
   * @param <T> the planning row type
   * @return the parsed list, or {@code null} when the carrier is blank or the array is empty
   * @throws JacksonException when the carrier holds malformed JSON or an unknown property
   */
  @Nullable
  private <T> List<T> parseCreateList(String json, TypeReference<List<T>> typeRef) {
    if (json == null || json.isBlank()) {
      return null;
    }
    List<T> list = objectMapper.readValue(json, typeRef);
    return (list == null || list.isEmpty()) ? null : list;
  }
}
