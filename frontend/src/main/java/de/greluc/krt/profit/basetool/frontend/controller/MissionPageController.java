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
 * Spring MVC controller for the mission read pages ({@code /missions} list, {@code /missions/{id}}
 * detail — the squadron's single coordination surface — and the {@code /missions/new} create form),
 * plus the unassigned-participants AJAX read.
 *
 * <p>The detail render is the largest read path in the project; it fans several backend calls out
 * through the {@code parallelPageLoader} and serves both the full page and, via {@code
 * fragment=...}, the individual section fragments the write side re-renders after a mutation. It
 * also carries the model-population helpers ({@code addFormsToModel}, {@code addOperationsToModel},
 * …) and the {@code propagateBackendError} problem+json re-emit that the write controller and
 * {@code MissionFinancePageController} reuse.
 *
 * <p>Since the #924 L5 read/write split this class keeps only that read-side surface. Every
 * state-mutating {@code /missions} endpoint — participants, units, crew, managers, frequencies and
 * their AJAX variants, including the sign-up paths that used to be reachable without a login
 * ({@code addParticipant}/{@code checkIn}/{@code checkOut}/{@code updatePayoutPreference}, members
 * only since ADR-0159) — moved verbatim to {@link MissionWriteController}, which delegates its
 * validation-failure re-renders back to this class.
 *
 * <p>REQ-SEC-052: the class-level {@code @PreAuthorize("isAuthenticated()")} is the floor. Every
 * handler here used to sit under a {@code permitAll} URL rule, and thirteen of them across this
 * package carried no gate of their own at all — protected by a matcher two folders away rather than
 * by anything next to the code. A method-level gate still wins where one is present.
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
   * Page size for the mission-detail finance ENTRIES table (ADR-0078). The summary strip reads its
   * totals from the SQL aggregate at {@code /finance-entries/summary}, so the table itself only
   * needs a bounded page instead of the previous {@code size=1000} load-all — keeping a finance
   * render from materializing thousands of rows under the multi-user live-update fan-out. The
   * backend independently caps the endpoint at 500.
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
   * Resolves the "registered member or above" predicate against the request {@link
   * org.springframework.security.core.Authentication} (the OAuth2 token authorities) — the same
   * source {@code sec:authorize}/{@code @PreAuthorize} use. Gating the member-only finance/refinery
   * fetches on the {@code OidcUser} principal's own authorities instead was the root cause of the
   * silently-empty "Finanzen" panel (REQ-SEC-013): Spring maps the Keycloak realm roles onto the
   * token, not the principal object.
   */
  private final FrontendAuthHelperService authHelperService;

  /**
   * Runs independent backend reads concurrently on virtual threads with the full request-scoped
   * context (SecurityContext / RequestAttributes / squadron / correlation id) restored, so the
   * mission-detail render does not pay the sum of their latencies in series. Used for the
   * member-only finance/sum/refinery-orders trio — three independent per-mission reads that
   * previously ran back to back on every render (and, since the live-sync presence relay #755, on
   * every peer's in-place fragment re-fetch too).
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
   * Seeds the various form-backing objects the mission-detail template needs (participant, crew,
   * unit, finance, manager, etc.) when they are not already present in the model. Authenticated
   * callers additionally get their own user record stuffed into the participant form so the "join
   * as me" default works without an extra fetch in the template.
   *
   * <p>The "join as me" prefill costs an uncached backend {@code GET /api/v1/users/me}, and the
   * add-participant modal it feeds is rendered only by the full page — never by any {@code
   * *-results} fragment. {@code prefillParticipantUser} therefore gates that fetch: fragment
   * refetches (which start with a fresh model and would otherwise re-issue the lookup on every
   * live-sync burst for a form no fragment dereferences, REQ-OBS/ADR-0078) pass {@code false} and
   * get an empty {@link ParticipantForm} instead, so no model attribute is missing (#1142).
   *
   * @param model Thymeleaf model populated with the seeded forms
   * @param principal authenticated OIDC user, or {@code null} for guests
   * @param prefillParticipantUser {@code true} to fetch {@code /users/me} and prefill the "join as
   *     me" participant form (full-page renders only); {@code false} to seed an empty form and skip
   *     the backend read (fragment refetches)
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
   * Renders the mission list ({@code /missions}). Public endpoint — guests see the full upcoming
   * mission catalog with sensitive fields stripped by the backend; authenticated callers see the
   * full record. Pagination + sort follow the standard URL-driven pattern.
   *
   * <p>Every caller-supplied value reaches the backend URI in the shape the backend's own {@code
   * GET /api/v1/missions/search} declares (REQ-SEC-051, ADR-0158): the free-text {@code search} as
   * a {@code WebClient} URI-template variable, encoded exactly once; {@code start} / {@code end}
   * bound as {@link Instant}; {@code status} narrowed to {@link #MISSION_STATUSES}. They used to be
   * concatenated into the URI string, where {@code &} in a search opened a second backend query
   * parameter, {@code #} cut the query off, {@code +} arrived as a space and {@code {x}} made the
   * template expansion throw (FE-SEC-01).
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
   * Renders the mission-detail page ({@code /missions/{id}}). Loads the mission, the finance
   * entries, the unit/crew/participant hierarchy, the manager list and the frequencies. The heavy
   * {@code addFormsToModel} call seeds every form-backing object the template needs for the inline
   * modals so the same controller method serves both fresh renders and post-flash re-renders after
   * a validation failure.
   *
   * <p>When {@code fragment} is set the same fully populated model is rendered through a single
   * Thymeleaf fragment instead of the whole page, so an in-place AJAX swap (epic #571) can
   * re-render one section after a sub-mutation: {@code crew-board} → the crew board, {@code
   * finance} → the finance &amp; payout pane, {@code mgmt} → the owner/manager management panel.
   * The full model is still built for every fragment value, so the fragment never references a
   * missing attribute.
   *
   * @param id the mission id
   * @param model the Spring MVC model populated with the mission aggregate and form backers
   * @param principal the authenticated user, or {@code null} for an anonymous/guest visitor
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
   * Renders the mission create form ({@code /missions/create}). Seeds the empty form plus the
   * reference catalogs (operations, job types, locations) so the dropdowns work.
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
   * Fetches the {@link OrgUnitMembershipOptionDto} list that drives the R5.d.d owner-picker on the
   * mission-create form. Mission creation has no explicit owner selector — the caller is the
   * implicit owner — so the picker reflects the caller's own memberships, not a separately-chosen
   * owner's. Falls back to an empty list when the lookup fails (the fragment collapses to a hidden
   * state for an empty option list).
   *
   * @param principal authenticated OIDC user; the picker is resolved server-side for the caller via
   *     {@code /api/v1/users/me/pickable-org-units}.
   * @return picker options or empty list; never {@code null}.
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
   * AJAX endpoint: returns all participants of a mission that are not yet assigned to any unit
   * crew. Used to populate the "Crew zuweisen" dropdown with only unassigned participants.
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
