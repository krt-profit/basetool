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

import de.greluc.krt.profit.basetool.frontend.model.dto.AddCrewRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddCustomFrequencyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddExternalParticipantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddFrequencyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddMissionStepRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddUnitRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateMissionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryUpdateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceTotalsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionObjectiveDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionStepDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PatchMissionCoreRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PatchMissionFlagsRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PatchMissionScheduleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ReorderMissionObjectivesRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ReorderMissionStepsRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetPartyLeadRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ToggleMissionStepRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateCrewRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateCustomFrequencyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateMissionOwnerRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateMissionOwningOrgUnitRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateMissionStepRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateParticipantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdatePayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateUnitRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the mission domain: the mission list and detail reads with their
 * catalogues, every mission section write, managers and ownership, and the mission finance ledger,
 * over {@link BackendApiClient} (plan §5.9, ADR-0032).
 *
 * <p>Methods ending in {@code Json} decode the backend's answer for an AJAX relay; their plain
 * twins send the identical request and discard the answer.
 */
@Service
@RequiredArgsConstructor
public class MissionBackendClient {

  private static final ParameterizedTypeReference<List<OperationReferenceDto>>
      OPERATION_REFERENCE_LIST = new ParameterizedTypeReference<List<OperationReferenceDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<MissionListDto>> MISSION_LIST_PAGE =
      new ParameterizedTypeReference<PageResponse<MissionListDto>>() {};

  private static final ParameterizedTypeReference<MissionDto> MISSION =
      new ParameterizedTypeReference<MissionDto>() {};

  private static final ParameterizedTypeReference<PageResponse<JobTypeDto>> JOB_TYPE_PAGE =
      new ParameterizedTypeReference<PageResponse<JobTypeDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> SQUADRON_PAGE =
      new ParameterizedTypeReference<PageResponse<SquadronDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<FrequencyTypeDto>>
      FREQUENCY_TYPE_PAGE = new ParameterizedTypeReference<PageResponse<FrequencyTypeDto>>() {};

  private static final ParameterizedTypeReference<List<ShipDto>> SHIP_LIST =
      new ParameterizedTypeReference<List<ShipDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<ShipTypeDto>> SHIP_TYPE_PAGE =
      new ParameterizedTypeReference<PageResponse<ShipTypeDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<MissionFinanceEntryDto>>
      MISSION_FINANCE_ENTRY_PAGE =
          new ParameterizedTypeReference<PageResponse<MissionFinanceEntryDto>>() {};

  private static final ParameterizedTypeReference<List<RefineryOrderListDto>> REFINERY_ORDER_LIST =
      new ParameterizedTypeReference<List<RefineryOrderListDto>>() {};

  private static final ParameterizedTypeReference<List<InventoryItemDto>> INVENTORY_ITEM_LIST =
      new ParameterizedTypeReference<List<InventoryItemDto>>() {};

  private static final ParameterizedTypeReference<List<MissionParticipantDto>> PARTICIPANT_LIST =
      new ParameterizedTypeReference<List<MissionParticipantDto>>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_MEMBERSHIP_OPTION_LIST = new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * The filters of one mission-list page, each already narrowed by the caller (REQ-SEC-051).
   *
   * @param query free-text filter; {@code null} or blank sends none
   * @param start inclusive lower bound on the planned start, or {@code null}
   * @param end inclusive upper bound on the planned start, or {@code null}
   * @param page zero-based page index, or {@code null}
   * @param size page size, or {@code null}
   * @param statuses the known statuses to filter on; empty selects the period's default set
   * @param period the effective period segment, {@code ALL}, {@code PAST} or {@code UPCOMING}
   */
  public record MissionSearch(
      @Nullable String query,
      @Nullable Instant start,
      @Nullable Instant end,
      @Nullable Integer page,
      @Nullable Integer size,
      @NotNull List<String> statuses,
      @NotNull String period) {}

  /**
   * Lists the operations a mission can be linked to.
   *
   * @return the operation references, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OperationReferenceDto> operationReferences() {
    return backendApiClient.get("/api/v1/operations/lookup", OPERATION_REFERENCE_LIST);
  }

  /**
   * Reads the signed-in user's own profile.
   *
   * @return the profile, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto currentUser() {
    return backendApiClient.get("/api/v1/users/me", UserDto.class);
  }

  /**
   * Reads one page of the mission overview, newest planned start first.
   *
   * @param search the page and its filters
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MissionListDto> searchMissions(@NotNull MissionSearch search) {
    StringBuilder uri = new StringBuilder("/api/v1/missions/search?");
    List<Object> uriVariables = new ArrayList<>();
    if (search.query() != null && !search.query().isBlank()) {
      uri.append("query={query}&");
      uriVariables.add(search.query());
    }
    if (search.start() != null) {
      uri.append("start={start}&");
      uriVariables.add(search.start());
    }
    if (search.end() != null) {
      uri.append("end={end}&");
      uriVariables.add(search.end());
    }
    if (search.page() != null) {
      uri.append("page={page}&");
      uriVariables.add(search.page());
    }
    if (search.size() != null) {
      uri.append("size={size}&");
      uriVariables.add(search.size());
    }
    uri.append("sort=plannedStartTime,desc&");
    if (search.statuses().isEmpty()) {
      switch (search.period()) {
        case "ALL" -> uri.append("status=PLANNED&status=ACTIVE&status=COMPLETED&status=CANCELLED&");
        case "PAST" -> uri.append("status=COMPLETED&status=CANCELLED&");
        default -> uri.append("status=PLANNED&status=ACTIVE&");
      }
    } else {
      for (String status : search.statuses()) {
        uri.append("status={status}&");
        uriVariables.add(status);
      }
    }
    return uriVariables.isEmpty()
        ? backendApiClient.get(uri.toString(), MISSION_LIST_PAGE)
        : backendApiClient.get(uri.toString(), MISSION_LIST_PAGE, uriVariables.toArray());
  }

  /**
   * Reads one mission with its whole aggregate.
   *
   * @param id the mission
   * @return the mission, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionDto mission(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/missions/{id}", MISSION, id);
  }

  /**
   * Re-reads one mission after a write, decoded by its class.
   *
   * @param id the mission
   * @return the mission, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionDto reloadMission(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/missions/{id}", MissionDto.class, id);
  }

  /**
   * Reads the cached mission job-type catalogue.
   *
   * @return the catalogue page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<JobTypeDto> missionJobTypes() {
    return backendApiClient.getCached(CachedCatalog.JOB_TYPES_MISSION, JOB_TYPE_PAGE);
  }

  /**
   * Reads the cached crew job-type catalogue.
   *
   * @return the catalogue page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<JobTypeDto> crewJobTypes() {
    return backendApiClient.getCached(CachedCatalog.JOB_TYPES_CREW, JOB_TYPE_PAGE);
  }

  /**
   * Reads the cached squadron catalogue.
   *
   * @return the catalogue page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SquadronDto> squadrons() {
    return backendApiClient.getCached(CachedCatalog.SQUADRONS_UNSORTED, SQUADRON_PAGE);
  }

  /**
   * Reads the cached active org units for the guest picker.
   *
   * @return the org-unit options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> activeOrgUnits() {
    return backendApiClient.getCached(
        CachedCatalog.ORG_UNITS_ACTIVE, ORG_UNIT_MEMBERSHIP_OPTION_LIST);
  }

  /**
   * Reads the cached active frequency-type catalogue.
   *
   * @return the catalogue page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<FrequencyTypeDto> frequencyTypes() {
    return backendApiClient.getCached(CachedCatalog.FREQUENCY_TYPES_ACTIVE, FREQUENCY_TYPE_PAGE);
  }

  /**
   * Reads the cached ship-type catalogue.
   *
   * @return the catalogue page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<ShipTypeDto> shipTypes() {
    return backendApiClient.getCached(CachedCatalog.SHIP_TYPES, SHIP_TYPE_PAGE);
  }

  /**
   * Lists the ships a unit of the mission can be assigned.
   *
   * @param id the mission
   * @return the ships, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ShipDto> unitShipOptions(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/missions/{id}/unit-ship-options", SHIP_LIST, id);
  }

  /**
   * Reads the aggregated finance totals of a mission (ADR-0078).
   *
   * @param id the mission
   * @return the totals, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionFinanceTotalsDto financeTotals(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/missions/{id}/finance-entries/summary", MissionFinanceTotalsDto.class, id);
  }

  /**
   * Reads the first page of a mission's finance entries.
   *
   * @param id the mission
   * @param size the page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MissionFinanceEntryDto> financeEntries(@NotNull UUID id, int size) {
    return backendApiClient.get(
        "/api/v1/missions/{id}/finance-entries?size={size}", MISSION_FINANCE_ENTRY_PAGE, id, size);
  }

  /**
   * Lists the refinery orders linked to a mission.
   *
   * @param id the mission
   * @return the orders, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<RefineryOrderListDto> refineryOrders(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/refinery-orders/mission/{id}", REFINERY_ORDER_LIST, id);
  }

  /**
   * Lists the inventory items linked to a mission.
   *
   * @param id the mission
   * @return the items, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<InventoryItemDto> inventory(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/inventory/mission/{id}", INVENTORY_ITEM_LIST, id);
  }

  /**
   * Lists the org units the caller may pick as a mission's owner.
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> pickableOrgUnits() {
    return backendApiClient.get(
        "/api/v1/users/me/pickable-org-units", ORG_UNIT_MEMBERSHIP_OPTION_LIST);
  }

  /**
   * Lists the mission's participants not yet assigned to a crew.
   *
   * @param id the mission
   * @return the participants, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionParticipantDto> unassignedParticipants(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/missions/{id}/participants/unassigned", PARTICIPANT_LIST, id);
  }

  /**
   * Reads the refinery rounding-mode setting.
   *
   * @return the setting, or {@code null} when the backend sent no body
   */
  @Nullable
  public SystemSettingDto refineryRoundingMode() {
    return backendApiClient.get("/api/v1/settings/refinery.rounding.mode", SystemSettingDto.class);
  }

  /**
   * Adds a participant through the classic form post.
   *
   * @param id the mission
   * @param request the participant
   */
  public void addParticipant(@NotNull UUID id, @NotNull AddExternalParticipantRequest request) {
    backendApiClient.post("/api/v1/missions/{id}/participants/add", request, Void.class, id);
  }

  /**
   * Adds a participant and answers the resulting participant list.
   *
   * @param id the mission
   * @param request the participant
   * @return the participant list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionParticipantDto> addParticipantJson(
      @NotNull UUID id, @NotNull AddExternalParticipantRequest request) {
    return listOf(
        backendApiClient.post(
            "/api/v1/missions/{id}/participants/slim", request, MissionParticipantDto[].class, id));
  }

  /**
   * Edits a participant.
   *
   * @param id the mission
   * @param participantId the participant
   * @param request the participant fields, carrying the optimistic-lock version
   */
  public void updateParticipant(
      @NotNull UUID id, @NotNull UUID participantId, @NotNull UpdateParticipantRequest request) {
    backendApiClient.put(
        "/api/v1/missions/{id}/participants/{participantId}/slim",
        request,
        Void.class,
        id,
        participantId);
  }

  /**
   * Edits a participant and answers the updated participant.
   *
   * @param id the mission
   * @param participantId the participant
   * @param request the participant fields, carrying the optimistic-lock version
   * @return the participant, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionParticipantDto updateParticipantJson(
      @NotNull UUID id, @NotNull UUID participantId, @NotNull UpdateParticipantRequest request) {
    return backendApiClient.put(
        "/api/v1/missions/{id}/participants/{participantId}/slim",
        request,
        MissionParticipantDto.class,
        id,
        participantId);
  }

  /**
   * Removes a participant.
   *
   * @param id the mission
   * @param participantId the participant
   */
  public void deleteParticipant(@NotNull UUID id, @NotNull UUID participantId) {
    backendApiClient.delete(
        "/api/v1/missions/{id}/participants/{participantId}/slim", Void.class, id, participantId);
  }

  /**
   * Checks a participant in.
   *
   * @param id the mission
   * @param participantId the participant
   */
  public void checkInParticipant(@NotNull UUID id, @NotNull UUID participantId) {
    backendApiClient.post(
        "/api/v1/missions/{id}/participants/{participantId}/check-in/slim",
        null,
        Void.class,
        id,
        participantId);
  }

  /**
   * Checks a participant in and answers the updated participant.
   *
   * @param id the mission
   * @param participantId the participant
   * @return the participant, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionParticipantDto checkInParticipantJson(
      @NotNull UUID id, @NotNull UUID participantId) {
    return backendApiClient.post(
        "/api/v1/missions/{id}/participants/{participantId}/check-in/slim",
        null,
        MissionParticipantDto.class,
        id,
        participantId);
  }

  /**
   * Checks a participant out.
   *
   * @param id the mission
   * @param participantId the participant
   */
  public void checkOutParticipant(@NotNull UUID id, @NotNull UUID participantId) {
    backendApiClient.post(
        "/api/v1/missions/{id}/participants/{participantId}/check-out/slim",
        null,
        Void.class,
        id,
        participantId);
  }

  /**
   * Checks a participant out and answers the updated participant.
   *
   * @param id the mission
   * @param participantId the participant
   * @return the participant, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionParticipantDto checkOutParticipantJson(
      @NotNull UUID id, @NotNull UUID participantId) {
    return backendApiClient.post(
        "/api/v1/missions/{id}/participants/{participantId}/check-out/slim",
        null,
        MissionParticipantDto.class,
        id,
        participantId);
  }

  /**
   * Sets one participant's payout preference.
   *
   * @param id the mission
   * @param participantId the participant
   * @param request the new preference
   * @return the updated participant, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionParticipantDto updatePayoutPreference(
      @NotNull UUID id,
      @NotNull UUID participantId,
      @Nullable UpdatePayoutPreferenceRequest request) {
    return backendApiClient.put(
        "/api/v1/missions/{id}/participants/{participantId}/payout-preference/slim",
        request,
        MissionParticipantDto.class,
        id,
        participantId);
  }

  /**
   * Assigns or clears the mission's party lead.
   *
   * @param id the mission
   * @param request the lead's user or handle, and the party-lead version
   */
  public void setPartyLead(@NotNull UUID id, @NotNull SetPartyLeadRequest request) {
    backendApiClient.put("/api/v1/missions/{id}/party-lead", request, Void.class, id);
  }

  /**
   * Hands the mission to another owner.
   *
   * @param id the mission
   * @param request the new owner and the ownership version
   */
  public void setOwner(@NotNull UUID id, @NotNull UpdateMissionOwnerRequest request) {
    backendApiClient.put("/api/v1/missions/{id}/owner", request, Void.class, id);
  }

  /**
   * Reassigns the mission's owning org unit (REQ-ORG-018).
   *
   * @param id the mission
   * @param request the owning org unit and its version
   */
  public void setOwningOrgUnit(
      @NotNull UUID id, @NotNull UpdateMissionOwningOrgUnitRequest request) {
    backendApiClient.put("/api/v1/missions/{id}/owning-org-unit", request, Void.class, id);
  }

  /**
   * Patches the mission's schedule section.
   *
   * @param id the mission
   * @param request the five schedule instants and the schedule version
   */
  public void patchSchedule(@NotNull UUID id, @NotNull PatchMissionScheduleRequest request) {
    backendApiClient.patch("/api/v1/missions/{id}/schedule", request, Void.class, id);
  }

  /**
   * Patches the mission's core section.
   *
   * @param id the mission
   * @param request the core fields and the core version
   */
  public void patchCore(@NotNull UUID id, @NotNull PatchMissionCoreRequest request) {
    backendApiClient.patch("/api/v1/missions/{id}/core", request, Void.class, id);
  }

  /**
   * Patches the mission's flags section.
   *
   * @param id the mission
   * @param request the internal flag and the flags version
   */
  public void patchFlags(@NotNull UUID id, @NotNull PatchMissionFlagsRequest request) {
    backendApiClient.patch("/api/v1/missions/{id}/flags", request, Void.class, id);
  }

  /**
   * Creates a mission.
   *
   * @param request the new mission
   * @return the created mission, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionDto createMission(@NotNull CreateMissionRequest request) {
    return backendApiClient.post("/api/v1/missions", request, MissionDto.class);
  }

  /**
   * Deletes a mission.
   *
   * @param id the mission
   */
  public void deleteMission(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/missions/{id}", Void.class, id);
  }

  /**
   * Adds a co-manager to a mission.
   *
   * @param missionId the mission
   * @param userId the new co-manager
   */
  public void addManager(@NotNull UUID missionId, @NotNull UUID userId) {
    backendApiClient.post(
        "/api/v1/missions/{missionUuid}/managers/{userUuid}/slim",
        null,
        String.class,
        missionId,
        userId);
  }

  /**
   * Removes a co-manager from a mission.
   *
   * @param missionId the mission
   * @param userId the co-manager
   */
  public void removeManager(@NotNull UUID missionId, @NotNull UUID userId) {
    backendApiClient.delete(
        "/api/v1/missions/{missionUuid}/managers/{userUuid}/slim", Void.class, missionId, userId);
  }

  /**
   * Adds a unit.
   *
   * @param id the mission
   * @param request the unit
   */
  public void addUnit(@NotNull UUID id, @NotNull AddUnitRequest request) {
    backendApiClient.post("/api/v1/missions/{id}/units/slim", request, Void.class, id);
  }

  /**
   * Adds a unit and answers the resulting unit list.
   *
   * @param id the mission
   * @param request the unit
   * @return the unit list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionUnitDto> addUnitJson(@NotNull UUID id, @NotNull AddUnitRequest request) {
    return listOf(
        backendApiClient.post(
            "/api/v1/missions/{id}/units/slim", request, MissionUnitDto[].class, id));
  }

  /**
   * Edits a unit.
   *
   * @param id the mission
   * @param unitId the unit
   * @param request the unit fields
   */
  public void updateUnit(
      @NotNull UUID id, @NotNull UUID unitId, @NotNull UpdateUnitRequest request) {
    backendApiClient.put(
        "/api/v1/missions/{id}/units/{unitId}/slim", request, Void.class, id, unitId);
  }

  /**
   * Edits a unit and answers the updated unit.
   *
   * @param id the mission
   * @param unitId the unit
   * @param request the unit fields
   * @return the unit, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionUnitDto updateUnitJson(
      @NotNull UUID id, @NotNull UUID unitId, @NotNull UpdateUnitRequest request) {
    return backendApiClient.put(
        "/api/v1/missions/{id}/units/{unitId}/slim", request, MissionUnitDto.class, id, unitId);
  }

  /**
   * Removes a unit; its participants fall back to the default unit.
   *
   * @param id the mission
   * @param unitId the unit
   */
  public void deleteUnit(@NotNull UUID id, @NotNull UUID unitId) {
    backendApiClient.delete("/api/v1/missions/{id}/units/{unitId}/slim", Void.class, id, unitId);
  }

  /**
   * Adds a crew member to a unit.
   *
   * @param id the mission
   * @param unitId the unit
   * @param request the participant and its crew job types
   */
  public void addCrew(@NotNull UUID id, @NotNull UUID unitId, @NotNull AddCrewRequest request) {
    backendApiClient.post(
        "/api/v1/missions/{id}/units/{unitId}/crew/slim", request, Void.class, id, unitId);
  }

  /**
   * Adds a crew member to a unit and answers the resulting crew list.
   *
   * @param id the mission
   * @param unitId the unit
   * @param request the participant and its crew job types
   * @return the crew list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionCrewDto> addCrewJson(
      @NotNull UUID id, @NotNull UUID unitId, @NotNull AddCrewRequest request) {
    return listOf(
        backendApiClient.post(
            "/api/v1/missions/{id}/units/{unitId}/crew/slim",
            request,
            MissionCrewDto[].class,
            id,
            unitId));
  }

  /**
   * Edits a crew member.
   *
   * @param id the mission
   * @param unitId the unit
   * @param crewId the crew member
   * @param request the crew job types
   */
  public void updateCrew(
      @NotNull UUID id,
      @NotNull UUID unitId,
      @NotNull UUID crewId,
      @NotNull UpdateCrewRequest request) {
    backendApiClient.put(
        "/api/v1/missions/{id}/units/{unitId}/crew/{crewId}/slim",
        request,
        Void.class,
        id,
        unitId,
        crewId);
  }

  /**
   * Edits a crew member and answers the updated crew entry.
   *
   * @param id the mission
   * @param unitId the unit
   * @param crewId the crew member
   * @param request the crew job types
   * @return the crew entry, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionCrewDto updateCrewJson(
      @NotNull UUID id,
      @NotNull UUID unitId,
      @NotNull UUID crewId,
      @NotNull UpdateCrewRequest request) {
    return backendApiClient.put(
        "/api/v1/missions/{id}/units/{unitId}/crew/{crewId}/slim",
        request,
        MissionCrewDto.class,
        id,
        unitId,
        crewId);
  }

  /**
   * Removes a crew member.
   *
   * @param id the mission
   * @param unitId the unit
   * @param crewId the crew member
   */
  public void deleteCrew(@NotNull UUID id, @NotNull UUID unitId, @NotNull UUID crewId) {
    backendApiClient.delete(
        "/api/v1/missions/{id}/units/{unitId}/crew/{crewId}/slim", Void.class, id, unitId, crewId);
  }

  /**
   * Creates or updates a catalogue frequency of the mission.
   *
   * @param id the mission
   * @param request the frequency type and value
   */
  public void putFrequency(@NotNull UUID id, @NotNull AddFrequencyRequest request) {
    backendApiClient.post("/api/v1/missions/{id}/frequencies/slim", request, Void.class, id);
  }

  /**
   * Creates or updates a catalogue frequency and answers the resulting frequency list.
   *
   * @param id the mission
   * @param request the frequency type and value
   * @return the frequency list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionFrequencyDto> putFrequencyJson(
      @NotNull UUID id, @NotNull AddFrequencyRequest request) {
    return listOf(
        backendApiClient.post(
            "/api/v1/missions/{id}/frequencies/slim", request, MissionFrequencyDto[].class, id));
  }

  /**
   * Removes a frequency; the backend answers without a body.
   *
   * @param id the mission
   * @param frequencyId the frequency
   */
  public void deleteFrequency(@NotNull UUID id, @NotNull UUID frequencyId) {
    backendApiClient.delete(
        "/api/v1/missions/{id}/frequencies/{frequencyId}/slim", Void.class, id, frequencyId);
  }

  /**
   * Adds a custom frequency (REQ-MISSION-014).
   *
   * @param id the mission
   * @param request the frequency's name and value
   * @return the frequency list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionFrequencyDto> addCustomFrequency(
      @NotNull UUID id, @NotNull AddCustomFrequencyRequest request) {
    return listOf(
        backendApiClient.post(
            "/api/v1/missions/{id}/frequencies/custom/slim",
            request,
            MissionFrequencyDto[].class,
            id));
  }

  /**
   * Edits a custom frequency under its row version (REQ-MISSION-014).
   *
   * @param id the mission
   * @param frequencyId the custom frequency
   * @param request the frequency's name, value and version
   * @return the frequency list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionFrequencyDto> updateCustomFrequency(
      @NotNull UUID id, @NotNull UUID frequencyId, @NotNull UpdateCustomFrequencyRequest request) {
    return listOf(
        backendApiClient.put(
            "/api/v1/missions/{id}/frequencies/custom/{frequencyId}/slim",
            request,
            MissionFrequencyDto[].class,
            id,
            frequencyId));
  }

  /**
   * Appends an Ablauf step.
   *
   * @param id the mission
   * @param request the step and the expected steps version
   * @return the ordered step list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionStepDto> addStep(@NotNull UUID id, @NotNull AddMissionStepRequest request) {
    return listOf(
        backendApiClient.post(
            "/api/v1/missions/{id}/steps/slim", request, MissionStepDto[].class, id));
  }

  /**
   * Edits an Ablauf step.
   *
   * @param id the mission
   * @param stepId the step
   * @param request the step and the expected steps version
   * @return the ordered step list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionStepDto> updateStep(
      @NotNull UUID id, @NotNull UUID stepId, @NotNull UpdateMissionStepRequest request) {
    return listOf(
        backendApiClient.put(
            "/api/v1/missions/{id}/steps/{stepId}/slim",
            request,
            MissionStepDto[].class,
            id,
            stepId));
  }

  /**
   * Removes an Ablauf step.
   *
   * @param id the mission
   * @param stepId the step
   * @param stepsVersion the expected steps version
   * @return the ordered step list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionStepDto> deleteStep(
      @NotNull UUID id, @NotNull UUID stepId, @NotNull Long stepsVersion) {
    return listOf(
        backendApiClient.delete(
            "/api/v1/missions/{id}/steps/{stepId}/slim?stepsVersion={stepsVersion}",
            MissionStepDto[].class,
            id,
            stepId,
            stepsVersion));
  }

  /**
   * Reorders the Ablauf steps.
   *
   * @param id the mission
   * @param request the step-id order and the expected steps version
   * @return the ordered step list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionStepDto> reorderSteps(
      @NotNull UUID id, @NotNull ReorderMissionStepsRequest request) {
    return listOf(
        backendApiClient.put(
            "/api/v1/missions/{id}/steps/reorder/slim", request, MissionStepDto[].class, id));
  }

  /**
   * Sets an Ablauf step's shared done flag.
   *
   * @param id the mission
   * @param stepId the step
   * @param request the done state and the expected steps version
   * @return the ordered step list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionStepDto> setStepDone(
      @NotNull UUID id, @NotNull UUID stepId, @NotNull ToggleMissionStepRequest request) {
    return listOf(
        backendApiClient.patch(
            "/api/v1/missions/{id}/steps/{stepId}/done/slim",
            request,
            MissionStepDto[].class,
            id,
            stepId));
  }

  /**
   * Appends a goal.
   *
   * @param id the mission
   * @param request the goal and the expected objectives version
   * @return the ordered goal list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionObjectiveDto> addObjective(
      @NotNull UUID id, @NotNull AddMissionObjectiveRequest request) {
    return listOf(
        backendApiClient.post(
            "/api/v1/missions/{id}/objectives/slim", request, MissionObjectiveDto[].class, id));
  }

  /**
   * Edits a goal.
   *
   * @param id the mission
   * @param objectiveId the goal
   * @param request the goal and the expected objectives version
   * @return the ordered goal list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionObjectiveDto> updateObjective(
      @NotNull UUID id, @NotNull UUID objectiveId, @NotNull UpdateMissionObjectiveRequest request) {
    return listOf(
        backendApiClient.put(
            "/api/v1/missions/{id}/objectives/{objectiveId}/slim",
            request,
            MissionObjectiveDto[].class,
            id,
            objectiveId));
  }

  /**
   * Removes a goal.
   *
   * @param id the mission
   * @param objectiveId the goal
   * @param objectivesVersion the expected objectives version
   * @return the ordered goal list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionObjectiveDto> deleteObjective(
      @NotNull UUID id, @NotNull UUID objectiveId, @NotNull Long objectivesVersion) {
    return listOf(
        backendApiClient.delete(
            "/api/v1/missions/{id}/objectives/{objectiveId}/slim?objectivesVersion={version}",
            MissionObjectiveDto[].class,
            id,
            objectiveId,
            objectivesVersion));
  }

  /**
   * Reorders the goals.
   *
   * @param id the mission
   * @param request the goal-id order and the expected objectives version
   * @return the ordered goal list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionObjectiveDto> reorderObjectives(
      @NotNull UUID id, @NotNull ReorderMissionObjectivesRequest request) {
    return listOf(
        backendApiClient.put(
            "/api/v1/missions/{id}/objectives/reorder/slim",
            request,
            MissionObjectiveDto[].class,
            id));
  }

  /**
   * Books a finance entry on a mission.
   *
   * @param request the entry, carrying its mission
   */
  public void addFinanceEntry(@NotNull MissionFinanceEntryCreateDto request) {
    backendApiClient.post("/api/v1/finance-entries", request, Void.class);
  }

  /**
   * Books a finance entry and answers the created entry.
   *
   * @param request the entry, carrying its mission
   * @return the entry, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionFinanceEntryDto addFinanceEntryJson(@NotNull MissionFinanceEntryCreateDto request) {
    return backendApiClient.post("/api/v1/finance-entries", request, MissionFinanceEntryDto.class);
  }

  /**
   * Edits a finance entry.
   *
   * @param entryId the entry
   * @param request the entry fields, carrying the optimistic-lock version
   */
  public void updateFinanceEntry(
      @NotNull UUID entryId, @NotNull MissionFinanceEntryUpdateDto request) {
    backendApiClient.put("/api/v1/finance-entries/{entryId}", request, Void.class, entryId);
  }

  /**
   * Edits a finance entry and answers the updated entry.
   *
   * @param entryId the entry
   * @param request the entry fields, carrying the optimistic-lock version
   * @return the entry, or {@code null} when the backend sent no body
   */
  @Nullable
  public MissionFinanceEntryDto updateFinanceEntryJson(
      @NotNull UUID entryId, @NotNull MissionFinanceEntryUpdateDto request) {
    return backendApiClient.put(
        "/api/v1/finance-entries/{entryId}", request, MissionFinanceEntryDto.class, entryId);
  }

  /**
   * Deletes a finance entry.
   *
   * @param entryId the entry
   */
  public void deleteFinanceEntry(@NotNull UUID entryId) {
    backendApiClient.delete("/api/v1/finance-entries/{entryId}", Void.class, entryId);
  }

  /**
   * Wraps a decoded JSON array as a fixed-size list backed by it.
   *
   * @param rows the decoded array, or {@code null}
   * @param <T> the element type
   * @return the list, or {@code null} when the backend sent no body
   */
  @Nullable
  private static <T> List<T> listOf(T @Nullable [] rows) {
    return rows == null ? null : Arrays.asList(rows);
  }
}
