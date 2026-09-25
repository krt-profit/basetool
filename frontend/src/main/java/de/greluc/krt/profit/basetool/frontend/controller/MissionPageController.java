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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceTotalsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.CrewForm;
import de.greluc.krt.profit.basetool.frontend.model.form.MissionForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ParticipantForm;
import de.greluc.krt.profit.basetool.frontend.model.form.UnitForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import de.greluc.krt.profit.basetool.frontend.support.RelayParams;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Spring MVC controller for the mission read pages: the {@code /missions} list, the {@code
 * /missions/{id}} detail page with its section fragments, the create form, and the
 * unassigned-participants AJAX read.
 *
 * <p>Also provides the model-population helpers and {@code propagateBackendError} used by {@link
 * MissionWriteController} and {@code MissionFinancePageController}. The class-level {@code
 * isAuthenticated()} gate is the floor for every handler (REQ-SEC-052).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MissionPageController {

  /** Response type for the {@code /api/v1/operations/lookup} reference-list read. */
  private static final ParameterizedTypeReference<List<OperationReferenceDto>>
      OPERATION_REFERENCE_LIST = new ParameterizedTypeReference<List<OperationReferenceDto>>() {};

  /** Response type for the paged {@code /api/v1/missions/search} mission-overview read. */
  private static final ParameterizedTypeReference<PageResponse<MissionListDto>> MISSION_LIST_PAGE =
      new ParameterizedTypeReference<PageResponse<MissionListDto>>() {};

  /**
   * The mission statuses {@code GET /api/v1/missions/search} filters on — the only {@code status}
   * values the list page relays (REQ-SEC-051). The backend stores the status as a string, so this
   * set is the vocabulary rather than an enum.
   */
  static final Set<String> MISSION_STATUSES = Set.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELLED");

  /** Response type for the single-mission {@code /api/v1/missions/{id}} read. */
  private static final ParameterizedTypeReference<MissionDto> MISSION =
      new ParameterizedTypeReference<MissionDto>() {};

  /**
   * Response type for the paged job-type / squadron / frequency-type catalog reads, whose rows are
   * consumed as untyped {@code Map} attributes by the mission-detail template.
   */
  private static final ParameterizedTypeReference<PageResponse<Map<String, Object>>>
      STRING_OBJECT_MAP_PAGE =
          new ParameterizedTypeReference<PageResponse<Map<String, Object>>>() {};

  /** Response type for the {@code /api/v1/missions/{id}/unit-ship-options} ship-picker read. */
  private static final ParameterizedTypeReference<List<ShipDto>> SHIP_LIST =
      new ParameterizedTypeReference<List<ShipDto>>() {};

  /** Response type for the paged {@code /api/v1/ship-types} catalog read. */
  private static final ParameterizedTypeReference<PageResponse<ShipTypeDto>> SHIP_TYPE_PAGE =
      new ParameterizedTypeReference<PageResponse<ShipTypeDto>>() {};

  /** Response type for the paged {@code /api/v1/missions/{id}/finance-entries} ledger read. */
  private static final ParameterizedTypeReference<PageResponse<MissionFinanceEntryDto>>
      MISSION_FINANCE_ENTRY_PAGE =
          new ParameterizedTypeReference<PageResponse<MissionFinanceEntryDto>>() {};

  /**
   * Page size of the mission-detail finance entries table; the totals come from the summary
   * aggregate (ADR-0078).
   */
  private static final int FINANCE_TABLE_PAGE_SIZE = 200;

  /** Response type for the {@code /api/v1/refinery-orders/mission/{id}} order-list read. */
  private static final ParameterizedTypeReference<List<RefineryOrderListDto>> REFINERY_ORDER_LIST =
      new ParameterizedTypeReference<List<RefineryOrderListDto>>() {};

  /** Response type for the {@code /api/v1/inventory/mission/{id}} item-list read (#1138). */
  private static final ParameterizedTypeReference<List<InventoryItemDto>> INVENTORY_ITEM_LIST =
      new ParameterizedTypeReference<List<InventoryItemDto>>() {};

  /** Response type for the untyped-JSON {@code participants/unassigned} passthrough read. */
  private static final ParameterizedTypeReference<Object> OBJECT =
      new ParameterizedTypeReference<Object>() {};

  /** Response type for the single-setting {@code /api/v1/settings/{key}} read. */
  private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP =
      new ParameterizedTypeReference<Map<String, Object>>() {};

  /**
   * Response type for the org-unit option reads — the active-org-unit guest picker ({@code
   * /api/v1/org-units/active}) and the caller's pickable-org-unit owner picker ({@code
   * /api/v1/users/me/pickable-org-units}), both of which return a flat {@link
   * OrgUnitMembershipOptionDto} list.
   */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_MEMBERSHIP_OPTION_LIST = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Resolves the "registered member or above" predicate against the request's token authorities,
   * which carry the Keycloak realm roles (REQ-SEC-013).
   */
  private final FrontendAuthHelperService authHelperService;

  /**
   * Runs the independent member-only finance, sum and refinery-order reads of the detail render
   * concurrently, with the request-scoped context restored.
   */
  private final ParallelPageLoader parallelPageLoader;

  private void addOperationsToModel(Model model) {
    try {
      List<OperationReferenceDto> operations =
          backendApiClient.get("/api/v1/operations/lookup", OPERATION_REFERENCE_LIST);
      model.addAttribute("operationsList", operations);
    } catch (Exception e) {
      log.warn("Could not load operations", e);
      model.addAttribute("operationsList", List.of());
    }
  }

  /**
   * Seeds the form-backing objects the mission-detail template needs when they are not already in
   * the model.
   *
   * @param model Thymeleaf model populated with the seeded forms
   * @param principal authenticated OIDC user, or {@code null}
   * @param prefillParticipantUser {@code true} to fetch {@code /users/me} and prefill the "join as
   *     me" participant form (full-page renders); {@code false} to seed an empty form without the
   *     backend read (fragment refetches)
   */
  public void addFormsToModel(Model model, OidcUser principal, boolean prefillParticipantUser) {
    if (!model.containsAttribute("participantForm")) {
      ParticipantForm form =
          new ParticipantForm(null, "", null, null, "", List.of(), null, null, null, null);
      if (principal != null && prefillParticipantUser) {
        try {
          UserDto me = backendApiClient.get("/api/v1/users/me", UserDto.class);
          if (me != null) {
            String name =
                (me.displayName() != null && !me.displayName().isBlank())
                    ? me.displayName()
                    : me.username();
            form =
                new ParticipantForm(
                    me.id(), name, null, null, "", List.of(), null, null, null, null);
          }
        } catch (Exception e) {
          log.warn("Could not prefill participant form", e);
        }
      }
      model.addAttribute("participantForm", form);
    }
    if (!model.containsAttribute("unitForm")) {
      model.addAttribute("unitForm", new UnitForm("", null, null, false, null, null, null));
    }
    if (!model.containsAttribute("crewForm")) {
      model.addAttribute("crewForm", new CrewForm(null, null));
    }
    if (!model.containsAttribute("financeForm")) {
      model.addAttribute(
          "financeForm",
          new de.greluc.krt.profit.basetool.frontend.model.form.MissionFinanceEntryForm());
    }
  }

  /**
   * Web binder configuration scoped to this controller. Registers any custom property editors
   * needed for the mission forms (currently only the inherited default editors).
   *
   * @param binder Spring data binder for the current request
   */
  @InitBinder
  public void initBinder(@NotNull WebDataBinder binder) {
    binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
  }

  /**
   * Renders the mission list ({@code /missions}) with URL-driven filtering, paging and sorting.
   *
   * <p>Caller-supplied filters reach the backend as typed, individually encoded parameters
   * (REQ-SEC-051).
   *
   * @param search optional free-text filter
   * @param start optional inclusive lower bound on the planned start, ISO-8601 instant
   * @param end optional inclusive upper bound on the planned start, ISO-8601 instant
   * @param status optional status filter; values outside {@link #MISSION_STATUSES} are dropped
   * @param showPast whether the default status filter includes finished missions
   * @param page optional zero-based page index
   * @param size optional page size
   * @param fragment {@code "results"} to render only the results fragment for a live-filter swap
   * @param model the view model
   * @param principal the signed-in user
   * @return the {@code missions-index} view name, or the results fragment selector
   */
  @NotNull
  @GetMapping
  public String listMissions(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant end,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false, defaultValue = "false") boolean showPast,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    StringBuilder uri = new StringBuilder("/api/v1/missions/search?");
    List<Object> uriVariables = new ArrayList<>();
    if (search != null && !search.isBlank()) {
      uri.append("query={query}&");
      uriVariables.add(search);
    }
    if (start != null) {
      uri.append("start={start}&");
      uriVariables.add(start);
    }
    if (end != null) {
      uri.append("end={end}&");
      uriVariables.add(end);
    }
    if (page != null) {
      uri.append("page=").append(page).append("&");
    }
    if (size != null) {
      uri.append("size=").append(size).append("&");
    }
    uri.append("sort=plannedStartTime,desc&");

    List<String> knownStatuses =
        status == null
            ? List.of()
            : status.stream()
                .map(s -> RelayParams.oneOfOrNull(s, MISSION_STATUSES))
                .filter(Objects::nonNull)
                .toList();
    if (knownStatuses.isEmpty()) {
      if (showPast) {
        uri.append("status=PLANNED&status=ACTIVE&status=COMPLETED&status=CANCELLED&");
      } else {
        uri.append("status=PLANNED&status=ACTIVE&");
      }
    } else {
      for (String s : knownStatuses) {
        uri.append("status=").append(s).append("&");
      }
    }

    try {
      PageResponse<MissionListDto> missionsPage =
          uriVariables.isEmpty()
              ? backendApiClient.get(uri.toString(), MISSION_LIST_PAGE)
              : backendApiClient.get(uri.toString(), MISSION_LIST_PAGE, uriVariables.toArray());
      model.addAttribute("missions", missionsPage.content());
      model.addAttribute("missionsPage", missionsPage);
      model.addAttribute("search", search);
      model.addAttribute("start", start);
      model.addAttribute("end", end);
      model.addAttribute("showPast", showPast);
    } catch (Exception e) {
      log.error("Error loading missions", e);
      model.addAttribute("error", "error.missions.load");
    }
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "missions :: missionsResults";
    }
    return "missions";
  }

  /**
   * Renders the mission-detail page ({@code /missions/{id}}) with its full model, including every
   * form backer for the inline modals.
   *
   * <p>A {@code fragment} value ({@code crew-board}, {@code finance}, {@code mgmt}) renders only
   * that section from the same fully populated model, for in-place AJAX swaps.
   *
   * @param id the mission id
   * @param model the Spring MVC model populated with the mission aggregate and form backers
   * @param principal the authenticated user, or {@code null}
   * @param fragment the optional section key selecting an in-place fragment render
   * @return the {@code mission-detail} view name, or a {@code mission-detail :: <fragment>}
   *     selector
   */
  @NotNull
  @GetMapping("/{id}")
  public String missionDetail(
      @PathVariable @NotNull UUID id,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(required = false) String fragment) {
    try {
      MissionDto mission = backendApiClient.get("/api/v1/missions/" + id, MISSION);

      final boolean fullRender = fragment == null;
      final String frag = fullRender ? null : fragment.toLowerCase(java.util.Locale.ROOT);
      final boolean needMgmt = fullRender || "mgmt".equals(frag);
      final boolean needCrewBoard = fullRender || "crew-board".equals(frag);
      final boolean needFinance = fullRender || "finance".equals(frag);

      MissionDetailModelBuilder.MissionDetailViewModel detail =
          MissionDetailModelBuilder.build(mission);
      model.addAttribute("mission", mission);
      model.addAttribute("participants", detail.participants());
      model.addAttribute("participantsByLeadType", detail.participantsByLeadType());
      model.addAttribute("missionLeadTypes", detail.missionLeadTypes());
      model.addAttribute("factLeaderName", detail.factLeaderName());
      model.addAttribute("participantUserIds", detail.participantUserIds());
      model.addAttribute("assignedUnitByParticipantId", detail.assignedUnitByParticipantId());
      model.addAttribute("assignedUnitShipIds", detail.assignedUnitShipIds());
      model.addAttribute("unassignedParticipants", detail.unassignedParticipants());
      model.addAttribute("participantsById", detail.participantsById());
      model.addAttribute("participationPercentages", detail.participationPercentages());
      model.addAttribute("frequencyByTypeId", detail.frequencyByTypeId());
      model.addAttribute("customFrequencies", detail.customFrequencies());

      if (needMgmt) {
        model.addAttribute("ownerOptions", fetchCallerMembershipOptions(principal));
      } else {
        model.addAttribute("ownerOptions", List.of());
      }

      if (!model.containsAttribute("missionForm")) {
        model.addAttribute(
            "missionForm",
            new MissionForm(
                mission.name() != null ? mission.name() : "",
                mission.description() != null ? mission.description() : "",
                mission.calendarLink() != null ? mission.calendarLink() : "",
                mission.status() != null ? mission.status() : "",
                formatInstant(mission.meetingTime()),
                formatInstant(mission.plannedStartTime()),
                formatInstant(mission.plannedEndTime()),
                formatInstant(mission.actualStartTime()),
                formatInstant(mission.actualEndTime()),
                mission.isInternal() != null && mission.isInternal(),
                mission.operation() != null ? String.valueOf(mission.operation().id()) : null,
                mission.version(),
                mission.coreVersion(),
                mission.scheduleVersion(),
                mission.flagsVersion(),
                null,
                mission.meetingPoint(),
                null,
                null,
                true,
                true,
                true));
      }
      model.addAttribute("isNew", false);
      model.addAttribute("authUserId", CurrentUser.userIdText(principal));
      addFormsToModel(model, principal, fullRender);
      if (fullRender) {
        addOperationsToModel(model);
      }

      model.addAttribute("roundingMode", needFinance ? fetchRoundingMode() : "UP");

      try {
        PageResponse<Map<String, Object>> jobTypesPage =
            backendApiClient.getCached(CachedCatalog.JOB_TYPES_MISSION, STRING_OBJECT_MAP_PAGE);
        model.addAttribute("jobTypes", jobTypesPage.content());
      } catch (Exception ignored) {
      }

      try {
        PageResponse<Map<String, Object>> crewJobTypesPage =
            backendApiClient.getCached(CachedCatalog.JOB_TYPES_CREW, STRING_OBJECT_MAP_PAGE);
        model.addAttribute("crewJobTypes", crewJobTypesPage.content());
      } catch (Exception ignored) {
      }

      try {
        PageResponse<Map<String, Object>> squadronsPage =
            backendApiClient.getCached(CachedCatalog.SQUADRONS_UNSORTED, STRING_OBJECT_MAP_PAGE);
        model.addAttribute("squadrons", squadronsPage.content());
      } catch (Exception ignored) {
      }

      try {
        List<OrgUnitMembershipOptionDto> orgUnits =
            backendApiClient.getCached(
                CachedCatalog.ORG_UNITS_ACTIVE, ORG_UNIT_MEMBERSHIP_OPTION_LIST);
        model.addAttribute("orgUnits", orgUnits != null ? orgUnits : List.of());
      } catch (Exception e) {
        model.addAttribute("orgUnits", List.of());
      }

      try {
        PageResponse<Map<String, Object>> freqTypesPage =
            backendApiClient.getCached(
                CachedCatalog.FREQUENCY_TYPES_ACTIVE, STRING_OBJECT_MAP_PAGE);
        model.addAttribute("frequencyTypes", freqTypesPage.content());
      } catch (Exception ignored) {
      }

      Boolean canEdit = mission.canEdit();
      if (canEdit != null && canEdit && needCrewBoard) {
        try {
          List<ShipDto> unitShipOptions =
              backendApiClient.get("/api/v1/missions/" + id + "/unit-ship-options", SHIP_LIST);
          model.addAttribute("unitShipOptions", unitShipOptions);
        } catch (Exception ignored) {
        }
      }

      try {
        PageResponse<ShipTypeDto> allShipTypesPage =
            backendApiClient.getCached(CachedCatalog.SHIP_TYPES, SHIP_TYPE_PAGE);
        model.addAttribute("allShipTypes", allShipTypesPage.content());
      } catch (Exception ignored) {
      }

      if (authHelperService.isMemberOrAbove() && needFinance) {
        try {
          CompletableFuture<MissionFinanceTotalsDto> totalsFuture =
              parallelPageLoader.loadAsync(
                  () ->
                      backendApiClient.get(
                          "/api/v1/missions/" + id + "/finance-entries/summary",
                          MissionFinanceTotalsDto.class));
          CompletableFuture<PageResponse<MissionFinanceEntryDto>> entriesFuture =
              parallelPageLoader.loadAsync(
                  () ->
                      backendApiClient.get(
                          "/api/v1/missions/"
                              + id
                              + "/finance-entries?size="
                              + FINANCE_TABLE_PAGE_SIZE,
                          MISSION_FINANCE_ENTRY_PAGE));
          CompletableFuture<List<RefineryOrderListDto>> refineryFuture =
              parallelPageLoader.loadAsync(
                  () ->
                      backendApiClient.get(
                          "/api/v1/refinery-orders/mission/" + id, REFINERY_ORDER_LIST));
          CompletableFuture<List<InventoryItemDto>> inventoryFuture =
              parallelPageLoader.loadAsync(
                  () ->
                      backendApiClient.get("/api/v1/inventory/mission/" + id, INVENTORY_ITEM_LIST));
          CompletableFuture.allOf(totalsFuture, entriesFuture, refineryFuture, inventoryFuture)
              .join();

          MissionFinanceTotalsDto totals = totalsFuture.join();
          model.addAttribute("financeSum", totals.total());
          model.addAttribute("financeIncomeSum", totals.incomeSum());
          model.addAttribute("financeExpenseSum", totals.expenseSum());
          model.addAttribute("financeIncomeCount", totals.incomeCount());
          model.addAttribute("financeExpenseCount", totals.expenseCount());
          Integer registered = mission.registeredParticipants();
          model.addAttribute(
              "financePerShare",
              (totals.total() != null && registered != null && registered > 0)
                  ? totals
                      .total()
                      .divide(
                          java.math.BigDecimal.valueOf(registered),
                          0,
                          java.math.RoundingMode.HALF_UP)
                  : null);

          model.addAttribute("financeEntries", entriesFuture.join().content());
          model.addAttribute("refineryOrders", refineryFuture.join());
          model.addAttribute("inventoryEntries", inventoryFuture.join());
        } catch (Exception e) {
          Throwable cause =
              (e instanceof java.util.concurrent.CompletionException && e.getCause() != null)
                  ? e.getCause()
                  : e;
          log.error("Error loading finance entries, refinery orders or mission inventory", cause);
        }
      }

    } catch (Exception e) {
      log.error("Error loading mission details", e);
      if (fragment != null) {
        return "mission-detail :: fragmentError";
      }
      model.addAttribute("error", "error.mission.details.load");
      return "redirect:/missions?error=error.mission.details.load";
    }
    model.addAttribute("authUserId", CurrentUser.userIdText(principal));
    if (fragment != null) {
      return switch (fragment.toLowerCase(java.util.Locale.ROOT)) {
        case "crew-board" -> "mission-detail :: crewBoard";
        case "finance" -> "mission-detail :: financeSection";
        case "mgmt" -> "mission-detail :: mgmtPanels";
        case "overview" -> "mission-detail :: overviewSection";
        case "steps-editor" -> "mission-detail :: stepsEditor";
        case "objectives-editor" -> "mission-detail :: objectivesEditor";
        case "frequencies-editor" -> "mission-detail :: frequenciesEditor";
        case "organisation" -> "mission-detail :: organisationPanel";
        default -> "mission-detail";
      };
    }
    return "mission-detail";
  }

  /**
   * Renders the mission create form with the operation, job-type and location catalogs for its
   * dropdowns.
   *
   * @param model Thymeleaf model populated with the form and reference catalogs
   * @param principal authenticated OIDC user
   * @return the {@code mission-create} view name
   */
  @NotNull
  @GetMapping("/new")
  public String createMissionForm(
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(required = false) UUID operationId) {
    if (!model.containsAttribute("missionForm")) {
      model.addAttribute(
          "missionForm",
          new MissionForm(
              "",
              "",
              "",
              "PLANNED",
              "",
              "",
              "",
              "",
              "",
              false,
              operationId != null ? operationId.toString() : null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              true,
              true,
              true));
    }
    model.addAttribute("isNew", true);
    model.addAttribute(
        "mission",
        new MissionDto(
            null,
            "",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            true,
            null,
            null,
            null,
            null,
            0,
            0,
            null,
            null,
            null,
            null,
            0L,
            java.util.List.of(),
            0L,
            java.util.List.of(),
            0L,
            null,
            0L));
    addFormsToModel(model, principal, true);
    addOperationsToModel(model);
    model.addAttribute("ownerOptions", fetchCallerMembershipOptions(principal));
    return "mission-detail";
  }

  /**
   * Fetches the caller's own org-unit membership options for the owner picker of the mission-create
   * form.
   *
   * @param principal authenticated OIDC user; the options are resolved via {@code
   *     /api/v1/users/me/pickable-org-units}.
   * @return picker options, or an empty list when the lookup fails; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchCallerMembershipOptions(OidcUser principal) {
    if (principal == null) {
      return List.of();
    }
    try {
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.get(
              "/api/v1/users/me/pickable-org-units", ORG_UNIT_MEMBERSHIP_OPTION_LIST);
      return options != null ? options : List.of();
    } catch (Exception e) {
      log.warn("Failed to fetch pickable org units for mission-create owner-picker", e);
      return List.of();
    }
  }

  /**
   * Returns the mission's participants not yet assigned to any unit crew, for the "Crew zuweisen"
   * dropdown.
   */
  @GetMapping(
      value = "/{id}/participants/unassigned/ajax",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public org.springframework.http.ResponseEntity<Object> getUnassignedParticipantsAjax(
      @PathVariable @NotNull UUID id) {
    try {
      Object result =
          backendApiClient.get("/api/v1/missions/" + id + "/participants/unassigned", OBJECT);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Get unassigned participants (AJAX) failed: status={}, msg={}",
          e.getStatusCode(),
          e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("UNEXPECTED ERROR in getUnassignedParticipantsAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  @NotNull
  private String fetchRoundingMode() {
    try {
      Map<String, Object> setting =
          backendApiClient.get("/api/v1/settings/refinery.rounding.mode", STRING_OBJECT_MAP);
      if (setting != null && setting.get("value") != null) {
        return String.valueOf(setting.get("value"));
      }
    } catch (Exception e) {
      log.warn("Failed to fetch refinery rounding mode, using default UP");
    }
    return "UP";
  }

  /**
   * Display time zone for the mission schedule fields. The datetime-splitter renders/edits times in
   * the browser's local zone; the server-side {@link #formatInstant} / {@link #parseToInstant}
   * round trip uses this fixed zone for the zoneless local-datetime form the hidden input carries
   * when a field is rendered but never re-edited.
   */
  private static final java.time.ZoneId MISSION_TIME_ZONE = java.time.ZoneId.of("Europe/Berlin");

  private String formatInstant(Object instantObj) {
    if (instantObj == null) {
      return "";
    }
    try {
      java.time.Instant instant;
      if (instantObj instanceof java.time.Instant i) {
        instant = i;
      } else if (instantObj instanceof String s) {
        if (s.isBlank()) {
          return "";
        }
        instant = java.time.Instant.parse(s);
      } else {
        return String.valueOf(instantObj);
      }
      java.time.ZonedDateTime zdt = instant.atZone(MISSION_TIME_ZONE);
      return zdt.toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    } catch (Exception e) {
      log.warn("Failed to format instant: {}", instantObj);
      return String.valueOf(instantObj);
    }
  }
}
