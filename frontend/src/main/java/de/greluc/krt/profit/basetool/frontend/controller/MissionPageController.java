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

import de.greluc.krt.profit.basetool.frontend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceTotalsDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.form.CrewForm;
import de.greluc.krt.profit.basetool.frontend.model.form.MissionForm;
import de.greluc.krt.profit.basetool.frontend.model.form.ParticipantForm;
import de.greluc.krt.profit.basetool.frontend.model.form.UnitForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.core.ParameterizedTypeReference;
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
 * their AJAX variants, including the deliberately public guest-join paths ({@code
 * addParticipant}/{@code checkIn}/{@code checkOut}/{@code updatePayoutPreference}) — moved verbatim
 * to {@link MissionWriteController}, which delegates its validation-failure re-renders back to this
 * class.
 */
@Controller
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
public class MissionPageController {

  /** Response type for the {@code /api/v1/operations/lookup} reference-list read. */
  private static final ParameterizedTypeReference<List<OperationReferenceDto>>
      OPERATION_REFERENCE_LIST = new ParameterizedTypeReference<List<OperationReferenceDto>>() {};

  /** Response type for the paged {@code /api/v1/missions/search} mission-overview read. */
  private static final ParameterizedTypeReference<PageResponse<MissionListDto>> MISSION_LIST_PAGE =
      new ParameterizedTypeReference<PageResponse<MissionListDto>>() {};

  /** Response type for the single-mission {@code /api/v1/missions/{id}} read. */
  private static final ParameterizedTypeReference<MissionDto> MISSION =
      new ParameterizedTypeReference<MissionDto>() {};

  /** Response type for the {@code /api/v1/users/lookup} manager-picker reference-list read. */
  private static final ParameterizedTypeReference<List<UserReferenceDto>> USER_REFERENCE_LIST =
      new ParameterizedTypeReference<List<UserReferenceDto>>() {};

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

  private void addOperationsToModel(Model model, boolean isPublic) {
    if (isPublic) {
      model.addAttribute("operationsList", List.of());
      return;
    }
    try {
      List<OperationReferenceDto> operations =
          backendApiClient.get("/api/v1/operations/lookup", OPERATION_REFERENCE_LIST, false);
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
   * @param model Thymeleaf model populated with the seeded forms
   * @param principal authenticated OIDC user, or {@code null} for guests
   */
  public void addFormsToModel(Model model, OidcUser principal) {
    if (!model.containsAttribute("participantForm")) {
      ParticipantForm form =
          new ParticipantForm(null, "", null, null, "", List.of(), null, null, null, null);
      if (principal != null) {
        try {
          UserDto me = backendApiClient.get("/api/v1/users/me", UserDto.class);
          if (me != null) {
            String name =
                (me.displayName() != null && !me.displayName().isBlank())
                    ? me.displayName()
                    : me.username();
            // Org-unit affiliations for a registered participant are derived server-side from the
            // caller's memberships, so the form carries no org-unit prefill — the picker is
            // guest-only and hidden once a registered user is selected.
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
  public void initBinder(WebDataBinder binder) {
    binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
  }

  /**
   * Renders the mission list ({@code /missions}). Public endpoint — guests see the full upcoming
   * mission catalog with sensitive fields stripped by the backend; authenticated callers see the
   * full record. Pagination + sort follow the standard URL-driven pattern.
   *
   * @return the {@code missions-index} view name
   */
  @GetMapping
  public String listMissions(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String start,
      @RequestParam(required = false) String end,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false, defaultValue = "false") boolean showPast,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    StringBuilder uri = new StringBuilder("/api/v1/missions/search?");
    if (search != null && !search.isBlank()) {
      uri.append("query=").append(search).append("&");
    }
    if (start != null && !start.isBlank()) {
      uri.append("start=").append(start).append("&");
    }
    if (end != null && !end.isBlank()) {
      uri.append("end=").append(end).append("&");
    }
    if (page != null) {
      uri.append("page=").append(page).append("&");
    }
    if (size != null) {
      uri.append("size=").append(size).append("&");
    }
    // Mission overview default: newest planned start at the top (the mission furthest in the
    // future, descending by plannedStartTime). The backend API default for /api/v1/missions/search
    // is ASC via PaginationUtil; the missions page deliberately overrides that. PaginationUtil
    // already appends `id` as a stable tiebreaker for equal plannedStartTime values, so this
    // covers the deterministic ordering contract.
    uri.append("sort=plannedStartTime,desc&");

    if ((status == null || status.isEmpty())) {
      if (showPast && !authHelperService.isAnonymous()) {
        // Explicitly request all statuses ONLY if authenticated
        uri.append("status=PLANNED&status=ACTIVE&status=COMPLETED&status=CANCELLED&");
      } else {
        uri.append("status=PLANNED&status=ACTIVE&");
      }
    } else {
      for (String s : status) {
        uri.append("status=").append(s).append("&");
      }
    }

    try {
      boolean isPublic = authHelperService.isAnonymous();

      PageResponse<MissionListDto> missionsPage =
          backendApiClient.get(uri.toString(), MISSION_LIST_PAGE, isPublic);
      model.addAttribute("missions", missionsPage.content());
      model.addAttribute("missionsPage", missionsPage);
      model.addAttribute("search", search);
      model.addAttribute("start", start);
      model.addAttribute("end", end);
      model.addAttribute("showPast", showPast && !authHelperService.isAnonymous());
    } catch (Exception e) {
      log.error("Error loading missions", e);
      model.addAttribute("error", "error.missions.load");
    }
    // AJAX live-filter requests only need the results fragment.
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
  @GetMapping("/{id}")
  public String missionDetail(
      @PathVariable @NotNull UUID id,
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(required = false) String fragment) {
    try {
      MissionDto mission =
          backendApiClient.get("/api/v1/missions/" + id, MISSION, authHelperService.isAnonymous());

      // Fragment-gated reads (mission-scale hardening, ADR-0078): an in-place section refetch
      // (GET /missions/{id}?fragment=X) must issue ONLY the backend reads its own fragment renders,
      // not the full page's ~8-read fan-out. Without this, one peer's live-update refetch of e.g.
      // the crew board still pulled the finance ledger (size=1000) + manager pickers, so a
      // 200-viewer save burst multiplied into thousands of backend GETs, starved the DB pool and
      // tripped the shared circuit breaker into a fleet-wide outage. `fullRender` (the top-level
      // page load) keeps fetching everything; a fragment fetches only its slice. The attribute->
      // fragment mapping is verified against mission-detail.html (finance attrs -> financeSection,
      // allUsers/ownerOptions -> mgmtPanels, unitShipOptions -> unit modals) so a skipped read is
      // never dereferenced by the fragment actually rendered.
      final boolean fullRender = fragment == null;
      final String frag = fullRender ? null : fragment.toLowerCase(java.util.Locale.ROOT);
      final boolean needMgmt = fullRender || "mgmt".equals(frag);
      final boolean needCrewBoard = fullRender || "crew-board".equals(frag);
      final boolean needFinance = fullRender || "finance".equals(frag);

      // Sort participants and build groupings
      List<MissionParticipantDto> participants = new java.util.ArrayList<>(mission.participants());
      participants.sort(
          (p1, p2) -> {
            String name1 = extractParticipantName(p1);
            String name2 = extractParticipantName(p2);
            return name1.compareToIgnoreCase(name2);
          });

      Map<String, List<MissionParticipantDto>> participantsByLeadType = new java.util.HashMap<>();
      List<JobTypeDto> missionLeadTypes = new java.util.ArrayList<>();
      java.util.Set<UUID> addedLeadTypes = new java.util.HashSet<>();
      for (MissionParticipantDto p : participants) {
        JobTypeDto job = p.plannedMissionJobType();
        if (job != null && job.isLeadershipRole()) {
          UUID jobId = job.id();
          participantsByLeadType
              .computeIfAbsent(jobId.toString(), k -> new java.util.ArrayList<>())
              .add(p);
          if (addedLeadTypes.add(jobId)) {
            missionLeadTypes.add(job);
          }
        }
      }
      model.addAttribute("mission", mission);
      model.addAttribute("participants", participants);
      model.addAttribute("participantsByLeadType", participantsByLeadType);
      model.addAttribute("missionLeadTypes", missionLeadTypes);

      // Facts-bar "Leiter" (REQ-MISSION-013): the participant designated as Einsatzleiter — the one
      // whose planned mission job type is the single mission-lead designation
      // (JobType.isMissionLead)
      // — else the mission owner, else "none". The owner is redacted for outsiders, so a guest with
      // no Einsatzleiter assigned sees "none". A mission can have only one Einsatzleiter, so the
      // first
      // match is authoritative.
      String factLeaderName = null;
      for (MissionParticipantDto p : participants) {
        JobTypeDto job = p.plannedMissionJobType();
        if (job != null && Boolean.TRUE.equals(job.isMissionLead())) {
          if (p.user() != null) {
            factLeaderName = p.user().effectiveName();
          } else if (p.guestName() != null && !p.guestName().isBlank()) {
            factLeaderName = p.guestName();
          }
          break;
        }
      }
      if (factLeaderName == null && mission.owner() != null) {
        factLeaderName = mission.owner().effectiveName();
      }
      model.addAttribute("factLeaderName", factLeaderName);

      // User ids of every account-backed participant (guests have no account and thus no hangar
      // ships). The unit ADD modal offers only ships owned by these users; the EDIT modal also
      // keeps already-assigned ships (see assignedUnitShipIds) so a unit can only be crewed with a
      // ship brought by someone registered for the mission, without dropping an existing one.
      java.util.Set<UUID> participantUserIds = new java.util.HashSet<>();
      for (MissionParticipantDto p : participants) {
        if (p.user() != null && p.user().id() != null) {
          participantUserIds.add(p.user().id());
        }
      }
      model.addAttribute("participantUserIds", participantUserIds);

      // Sort crew members and build groupings
      Map<UUID, String> assignedUnitByParticipantId = new java.util.HashMap<>();
      // Ids of ships already pinned to a unit of this mission. The unit EDIT modal keeps offering
      // these even when the owner is no longer a participant, so editing an unrelated field on such
      // a unit doesn't silently drop the ship — the client-side picker pre-selects the current ship
      // by value and needs the <option> to exist.
      java.util.Set<UUID> assignedUnitShipIds = new java.util.HashSet<>();
      if (mission.assignedUnits() != null) {
        for (MissionUnitDto unit : mission.assignedUnits()) {
          if (unit.ship() != null && unit.ship().id() != null) {
            assignedUnitShipIds.add(unit.ship().id());
          }
          String unitName = unit.name() != null ? unit.name() : "";
          if (unit.crew() != null) {
            for (MissionCrewDto c : unit.crew()) {
              if (c.participantId() != null) {
                assignedUnitByParticipantId.merge(
                    c.participantId(), unitName, (oldVal, newVal) -> oldVal + " " + newVal);
              }
            }
          }
        }
      }
      model.addAttribute("assignedUnitByParticipantId", assignedUnitByParticipantId);
      model.addAttribute("assignedUnitShipIds", assignedUnitShipIds);

      // "Crew zuweisen"-Dropdown zeigt nur Teilnehmer, die noch keiner Einheit zugewiesen sind.
      // Sortierung wird aus `participants` (oben bereits alphabetisch nach extractParticipantName)
      // geerbt — ein assignment-Status-Filter ändert die Reihenfolge der verbleibenden Einträge
      // nicht. Der server-seitige Filter ist authoritativ; nach einer Crew-Zuweisung lädt der
      // AJAX-Pfad die ganze Seite neu, sodass das Dropdown auf dem aktuellen Stand bleibt.
      List<MissionParticipantDto> unassignedParticipants =
          participants.stream()
              .filter(p -> p.id() != null && !assignedUnitByParticipantId.containsKey(p.id()))
              .toList();
      model.addAttribute("unassignedParticipants", unassignedParticipants);

      // Crew board (tab layout, Variante B): unit crew lists carry only
      // participantId/participantName, so the board's person rows resolve the full participant
      // payload (org units, desired job, comment, check-in state, version) via this id lookup.
      Map<UUID, MissionParticipantDto> participantsById = new java.util.HashMap<>();
      for (MissionParticipantDto p : participants) {
        if (p.id() != null) {
          participantsById.put(p.id(), p);
        }
      }
      model.addAttribute("participantsById", participantsById);

      // Calculate participation percentages
      Map<UUID, Double> participationPercentages = new java.util.HashMap<>();
      for (MissionParticipantDto p : participants) {
        participationPercentages.put(p.id(), 0.0);
      }

      java.time.Instant missionStart = mission.actualStartTime();
      java.time.Instant missionEnd = mission.actualEndTime();

      if (missionStart != null) {
        long totalDurationSeconds = 0;

        Map<UUID, Long> participantDurations = new java.util.HashMap<>();

        for (MissionParticipantDto p : participants) {
          java.time.Instant participantStart = p.startTime();
          java.time.Instant participantEnd = p.endTime();

          if (participantStart != null) {
            java.time.Instant effectiveStart =
                participantStart.isBefore(missionStart) ? missionStart : participantStart;
            java.time.Instant effectiveEnd;
            if (participantEnd != null) {
              effectiveEnd =
                  (missionEnd != null && participantEnd.isAfter(missionEnd))
                      ? missionEnd
                      : participantEnd;
            } else {
              effectiveEnd = (missionEnd != null) ? missionEnd : java.time.Instant.now();
            }

            if (effectiveEnd.isAfter(effectiveStart)) {
              long duration = java.time.Duration.between(effectiveStart, effectiveEnd).getSeconds();
              participantDurations.put(p.id(), duration);
              totalDurationSeconds += duration;
            }
          }
        }

        if (totalDurationSeconds > 0) {
          for (MissionParticipantDto p : participants) {
            Long duration = participantDurations.get(p.id());
            if (duration != null) {
              double percentage = (double) duration / totalDurationSeconds * 100.0;
              participationPercentages.put(p.id(), percentage);
            }
          }
        }
      }
      model.addAttribute("participationPercentages", participationPercentages);

      // Build frequency lookup for the typed (global) channels, plus the ordered list of custom
      // (mission-specific) channels (REQ-MISSION-014) rendered in the "Weitere Frequenzen" editor
      // and the overview Funk panel. Custom rows carry a free-text name and no frequencyType; they
      // are sorted case-insensitively by label for a stable, reload-independent order.
      Map<String, MissionFrequencyDto> frequencyByTypeId = new java.util.HashMap<>();
      List<MissionFrequencyDto> customFrequencies = new java.util.ArrayList<>();
      if (mission.frequencies() != null) {
        for (MissionFrequencyDto f : mission.frequencies()) {
          if (f.frequencyTypeId() != null) {
            frequencyByTypeId.put(f.frequencyTypeId().toString(), f);
          } else if (f.name() != null) {
            customFrequencies.add(f);
          }
        }
      }
      customFrequencies.sort(
          java.util.Comparator.comparing(MissionFrequencyDto::name, String.CASE_INSENSITIVE_ORDER));
      model.addAttribute("frequencyByTypeId", frequencyByTypeId);
      model.addAttribute("customFrequencies", customFrequencies);

      // Fetch users + owner picker for the Verwaltung (mgmt) panel ONLY — the manager/owner selects
      // live in the mgmtPanels fragment, so a crew/finance/overview/steps/... refetch does not need
      // them (fragment-gating, see fullRender note above). Default to empty lists when skipped so a
      // stray reference never NPEs.
      if (!authHelperService.isAnonymous() && needMgmt) {
        try {
          List<UserReferenceDto> allUsers =
              backendApiClient.get("/api/v1/users/lookup", USER_REFERENCE_LIST, false);
          model.addAttribute("allUsers", allUsers);
        } catch (Exception e) {
          log.warn("Could not load users for manager selection", e);
        }
        // Owning-org-unit reassignment picker (REQ-ORG-018): the caller's assignable org units feed
        // the Verwaltung "Verantwortliche Einheit" control, mirroring the create-form owner-picker.
        model.addAttribute("ownerOptions", fetchCallerMembershipOptions(principal));
      } else {
        model.addAttribute("allUsers", List.of());
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
                // Edit path: owningOrgUnitId is not editable, the existing stamp survives.
                null,
                mission.meetingPoint(),
                // Ziele / Ablauf are edited via their own AJAX section editors on the edit page,
                // never through the create form's JSON carriers.
                null,
                null));
      }
      model.addAttribute("isNew", false);
      model.addAttribute("authUserId", principal != null ? principal.getSubject() : null);
      addFormsToModel(model, principal);
      addOperationsToModel(model, authHelperService.isAnonymous());

      // roundingMode only feeds the finance/refinery display; skip its backend read for non-finance
      // fragment refetches. The "UP" default matches fetchRoundingMode's own fallback.
      model.addAttribute(
          "roundingMode", needFinance ? fetchRoundingMode(authHelperService.isAnonymous()) : "UP");

      // Fetch Mission JobTypes
      try {
        PageResponse<Map<String, Object>> jobTypesPage =
            backendApiClient.getCached(
                CachedCatalog.JOB_TYPES_MISSION, STRING_OBJECT_MAP_PAGE, true);
        model.addAttribute("jobTypes", jobTypesPage.content());
      } catch (Exception e) {
        // Ignore if job types fail
      }

      // Fetch Crew JobTypes
      try {
        PageResponse<Map<String, Object>> crewJobTypesPage =
            backendApiClient.getCached(CachedCatalog.JOB_TYPES_CREW, STRING_OBJECT_MAP_PAGE, true);
        model.addAttribute("crewJobTypes", crewJobTypesPage.content());
      } catch (Exception e) {
        // Ignore
      }

      // Fetch Squadrons
      try {
        PageResponse<Map<String, Object>> squadronsPage =
            backendApiClient.getCached(
                CachedCatalog.SQUADRONS_UNSORTED, STRING_OBJECT_MAP_PAGE, true);
        model.addAttribute("squadrons", squadronsPage.content());
      } catch (Exception e) {
        // Ignore
      }

      // Fetch all active org units (Staffel + Spezialkommandos) for the guest org-unit picker in
      // the participant add/edit modals. The backend endpoint requires a role, and an anonymous
      // guest's submitted org units are dropped server-side (H-3) anyway, so the picker is only
      // populated for authenticated callers labeling a guest.
      if (!authHelperService.isAnonymous()) {
        try {
          List<OrgUnitMembershipOptionDto> orgUnits =
              backendApiClient.getCached(
                  CachedCatalog.ORG_UNITS_ACTIVE, ORG_UNIT_MEMBERSHIP_OPTION_LIST);
          model.addAttribute("orgUnits", orgUnits != null ? orgUnits : List.of());
        } catch (Exception e) {
          model.addAttribute("orgUnits", List.of());
        }
      } else {
        model.addAttribute("orgUnits", List.of());
      }

      // Fetch FrequencyTypes
      try {
        PageResponse<Map<String, Object>> freqTypesPage =
            backendApiClient.getCached(
                CachedCatalog.FREQUENCY_TYPES_ACTIVE, STRING_OBJECT_MAP_PAGE, true);
        model.addAttribute("frequencyTypes", freqTypesPage.content());
      } catch (Exception e) {
        // Ignore
      }

      // Fetch Ships (Only if authenticated)
      if (!authHelperService.isAnonymous()) {
        // Unit ship pickers are populated from the mission-scoped endpoint, not the caller's
        // OrgUnit-scoped hangar: it returns ships of registered participants (any OrgUnit) plus
        // ships already assigned to a unit. Only fetched when the caller may edit the mission —
        // otherwise the modals don't render and the endpoint would 403.
        // unit-ship-options only populates the unit add/edit modals (crew board area); skip its
        // backend read for finance/mgmt/overview/steps/... fragment refetches.
        Boolean canEdit = mission.canEdit();
        if (canEdit != null && canEdit && needCrewBoard) {
          try {
            List<ShipDto> unitShipOptions =
                backendApiClient.get(
                    "/api/v1/missions/" + id + "/unit-ship-options", SHIP_LIST, false);
            model.addAttribute("unitShipOptions", unitShipOptions);
          } catch (Exception e) {
            // Ignore, e.g. if the caller cannot manage the mission
          }
        }

        try {
          PageResponse<ShipTypeDto> allShipTypesPage =
              backendApiClient.getCached(CachedCatalog.SHIP_TYPES, SHIP_TYPE_PAGE);
          model.addAttribute("allShipTypes", allShipTypesPage.content());
        } catch (Exception e) {
          // Ignore, e.g. if user has no HANGAR_READ or other issue
        }
      }

      // Fetch Finance Entries and Refinery Orders — member-only (the finance ledger is the
      // mission's payout view). A guest is treated like an anonymous visitor here: the backend
      // would reject these reads with 403 anyway, and skipping them keeps the "Finanzen" panel
      // empty/collapsed instead of leaking refinery expenses through the shared finance table.
      if (authHelperService.isMemberOrAbove() && needFinance) {
        try {
          // ADR-0078 mission-scale hardening: the summary strip reads its totals from a single
          // backend SQL aggregate (/finance-entries/summary) instead of loading the whole ledger
          // and
          // summing in Java, and the entries table is bounded to a page instead of size=1000. Under
          // the multi-user live-update fan-out this stops a finance render from pinning a DB
          // connection on a thousand-row query. The three reads are independent per-mission lookups
          // run concurrently; on any failure the whole Finanzen panel collapses to its empty state.
          CompletableFuture<MissionFinanceTotalsDto> totalsFuture =
              parallelPageLoader.loadAsync(
                  () ->
                      backendApiClient.get(
                          "/api/v1/missions/" + id + "/finance-entries/summary",
                          MissionFinanceTotalsDto.class,
                          false));
          CompletableFuture<PageResponse<MissionFinanceEntryDto>> entriesFuture =
              parallelPageLoader.loadAsync(
                  () ->
                      backendApiClient.get(
                          "/api/v1/missions/"
                              + id
                              + "/finance-entries?size="
                              + FINANCE_TABLE_PAGE_SIZE,
                          MISSION_FINANCE_ENTRY_PAGE,
                          false));
          CompletableFuture<List<RefineryOrderListDto>> refineryFuture =
              parallelPageLoader.loadAsync(
                  () ->
                      backendApiClient.get(
                          "/api/v1/refinery-orders/mission/" + id, REFINERY_ORDER_LIST, false));
          CompletableFuture.allOf(totalsFuture, entriesFuture, refineryFuture).join();

          // Summary strip (Gesamtsumme / Einnahmen / Ausgaben / je Anteil) straight from the
          // aggregate — the expense figures already fold in refinery-order expenses backend-side.
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

          // Bounded entries table + the (small, bounded) refinery-order list for the ledger table.
          model.addAttribute("financeEntries", entriesFuture.join().content());
          model.addAttribute("refineryOrders", refineryFuture.join());
        } catch (Exception e) {
          // join() reports a supplier failure wrapped in a CompletionException; log its concrete
          // cause so the line still names the real backend exception. Any failure collapses the
          // whole Finanzen panel to its empty state.
          Throwable cause =
              (e instanceof java.util.concurrent.CompletionException && e.getCause() != null)
                  ? e.getCause()
                  : e;
          log.error("Error loading finance entries or refinery orders", cause);
        }
      }

    } catch (Exception e) {
      log.error("Error loading mission details", e);
      if (fragment != null) {
        // In-place fragment path (#571/#574): a redirect here would be followed by
        // krtFetch.swap and the whole /missions page painted into the small section
        // container. Answer with a section-sized inline error fragment instead — the swap
        // renders it in place. (An expired-session login redirect happens in the security
        // filter before this controller; krtFetch.swap catches that via res.redirected.)
        return "mission-detail :: fragmentError";
      }
      model.addAttribute("error", "error.mission.details.load");
      return "redirect:/missions?error=error.mission.details.load";
    }
    // Expose the authenticated user's JWT sub (Keycloak UUID) so Thymeleaf can
    // robustly decide whether a participant row belongs to the current user
    // and enable self-edit on the member's own entry.
    // NOTE: currentAuth.getName() returns the preferred_username (configured via
    // user-name-attribute),
    // NOT the Keycloak UUID. We must use principal.getSubject() to get the sub (UUID) that matches
    // p.user.id in the participant list.
    model.addAttribute("authUserId", principal != null ? principal.getSubject() : null);
    // In-place AJAX swap (epic #571): re-render only the section the caller mutated. The model is
    // built fragment-gated above (see fullRender), so each fragment renders with exactly the
    // attributes its own section needs — a section refetch no longer pays the full page's read
    // fan-out (ADR-0078).
    if (fragment != null) {
      return switch (fragment.toLowerCase(java.util.Locale.ROOT)) {
        case "crew-board" -> "mission-detail :: crewBoard";
        case "finance" -> "mission-detail :: financeSection";
        case "mgmt" -> "mission-detail :: mgmtPanels";
        case "overview" -> "mission-detail :: overviewSection";
        case "steps-editor" -> "mission-detail :: stepsEditor";
        case "objectives-editor" -> "mission-detail :: objectivesEditor";
        case "frequencies-editor" -> "mission-detail :: frequenciesEditor";
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
  @GetMapping("/new")
  @PreAuthorize("isAuthenticated()")
  public String createMissionForm(
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(required = false) UUID operationId) {
    if (!model.containsAttribute("missionForm")) {
      // operationId preselects the parent operation when the create form is opened from an
      // operation's Einsätze tab ("Einsatz hinzufügen"); null for the plain create flow.
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
              // objectivesJson / stepsJson: empty on a fresh create form; the client fills them
              // from the Ziele / Ablauf rows on submit.
              null,
              null));
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
            null));
    addFormsToModel(model, principal);
    addOperationsToModel(model, false);
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
      // Epic #692 Phase 5: drill-down owner picker — the caller's direct memberships plus their
      // cascading leadership reach (own Bereich/OL + overseen subordinate Staffeln/SKs). Unchanged
      // for an ordinary member.
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
  @PreAuthorize("isAuthenticated()")
  public org.springframework.http.ResponseEntity<Object> getUnassignedParticipantsAjax(
      @PathVariable @NotNull UUID id) {
    try {
      Object result =
          backendApiClient.get(
              "/api/v1/missions/" + id + "/participants/unassigned", OBJECT, false);
      return org.springframework.http.ResponseEntity.ok(result);
    } catch (de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
      log.debug(
          "Get unassigned participants (AJAX) failed: status={}, msg={}",
          e.getStatusCode(),
          e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.debug("UNEXPECTED ERROR in getUnassignedParticipantsAjax for mission {}", id, e);
      return org.springframework.http.ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Re-emits a backend {@link
   * de.greluc.krt.profit.basetool.frontend.service.BackendServiceException} as an {@code
   * application/problem+json} response that preserves the stable {@code code} and human-readable
   * {@code detail} from the upstream RFC 7807 body.
   *
   * <p>The mission-detail AJAX layer (see {@code krt-fetch.js}) reads {@code code} to decide
   * between a "stale data, reload?" prompt (only for {@code OPTIMISTIC_LOCK} / {@code
   * PESSIMISTIC_LOCK}) and a plain error toast for domain conflicts ({@code DUPLICATE_ENTITY},
   * {@code BUSINESS_CONFLICT}, …). Returning {@code .build()} with only the status code stripped
   * that signal and made every 409 look like an optimistic-lock conflict.
   *
   * @param e parsed backend exception with status + RFC 7807 fields
   * @return problem+json response mirroring the upstream status and body
   */
  // Package-private so the sibling MissionFinancePageController's /ajax handlers reuse the same
  // RFC 7807 passthrough instead of duplicating it.
  static org.springframework.http.ResponseEntity<Object> propagateBackendError(
      @NotNull de.greluc.krt.profit.basetool.frontend.service.BackendServiceException e) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("status", e.getStatusCode());
    body.put("code", e.getProblemCode());
    if (e.getProblemDetail() != null && !e.getProblemDetail().isBlank()) {
      body.put("detail", e.getProblemDetail());
    }
    if (e.getCorrelationId() != null && !e.getCorrelationId().isBlank()) {
      body.put("correlationId", e.getCorrelationId());
    }
    return org.springframework.http.ResponseEntity.status(e.getStatusCode())
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  private String extractParticipantName(MissionParticipantDto participant) {
    if (participant == null) {
      return "";
    }
    if (participant.user() != null) {
      if (participant.user().effectiveName() != null
          && !participant.user().effectiveName().isBlank()) {
        return participant.user().effectiveName();
      }
      if (participant.user().displayName() != null && !participant.user().displayName().isBlank()) {
        return participant.user().displayName();
      }
      if (participant.user().username() != null && !participant.user().username().isBlank()) {
        return participant.user().username();
      }
    }
    return participant.guestName() != null ? participant.guestName() : "";
  }

  private String fetchRoundingMode(boolean isPublic) {
    try {
      Map<String, Object> setting =
          backendApiClient.get(
              "/api/v1/settings/refinery.rounding.mode", STRING_OBJECT_MAP, isPublic);
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
      // Truncate to seconds: the splitter's date/time inputs are minute-granular and write back a
      // seconds-precision value on edit, so sub-second digits would only be DOM noise. The
      // microsecond local form (YYYY-MM-DDThh:mm:ss.SSSSSS) is also exactly what the splitter's
      // local-datetime regex cannot match and what parseToInstant used to choke on; truncating
      // keeps the value in the documented zoneless YYYY-MM-DDThh:mm[:ss] shape.
      return zdt.toLocalDateTime().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    } catch (Exception e) {
      log.warn("Failed to format instant: {}", instantObj);
      return String.valueOf(instantObj);
    }
  }
}
