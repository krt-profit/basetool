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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.LeitungUnitContext;
import de.greluc.krt.profit.basetool.frontend.model.dto.BereichChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungMemberDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungUnitDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungViewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronChartDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.MapPayloadValues;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Controller for the Leitung page ({@code /organisation/leitung}, REQ-ROLE-004), where leaders
 * appoint, change and remove the ranks their tier delegates to them in the org units the backend
 * returns as manageable, shown as a unit tree with the selected unit's detail.
 *
 * <p>Gated to {@link Roles#ADMIN_OR_OFFICER}; per-unit authorisation is enforced by the backend.
 * Write proxies relay backend failures as status plus {@code {code, detail}}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/organisation/leitung")
@RequiredArgsConstructor
@Slf4j
public class LeitungPageController {

  /** The Staffel tab that lists the Kommandogruppen. */
  private static final String TAB_GROUPS = "groups";

  /** The default tab of every unit: its members. */
  private static final String TAB_MEMBERS = "members";

  /** The roster role of a Spezialkommando's lead. */
  private static final String SK_LEAD = "SK_LEAD";

  /** Response type of the raw-JSON Spezialkommando roster read. */
  private static final ParameterizedTypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the Leitung page as unit tree and unit detail, or only its {@code leitungSections}
   * fragment for an in-place swap.
   *
   * <p>Besides {@code leitung} the model carries {@code selectedUnitId} (the requested unit when
   * the view lists it, else the first listed unit), {@code selectedTab}, {@code unitContext}
   * (Bereich and department per unit, read from the org chart; empty when that read fails), {@code
   * selfUserId} and, per Spezialkommando, {@code skRosters} plus {@code skFlagsKnown} for the units
   * whose role flags the caller may read.
   *
   * @param fragment {@code "leitungSections"} for the fragment only; otherwise the full page.
   * @param unit the id of the unit to show, from the {@code ?unit=} deep link; may be absent.
   * @param tab {@code "groups"} to open a Staffel's Kommandogruppen tab; otherwise its members.
   * @param model the Thymeleaf model.
   * @return the view name, or its {@code leitungSections} selector for the fragment path.
   */
  @NotNull
  @GetMapping
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public String leitung(
      @RequestParam(required = false) String fragment,
      @RequestParam(required = false) String unit,
      @RequestParam(required = false) String tab,
      Model model) {
    LeitungViewDto view = null;
    try {
      view = backendApiClient.get("/api/v1/leitung/view", LeitungViewDto.class);
      model.addAttribute("leitung", view);
    } catch (BackendServiceException e) {
      log.debug("Failed to load Leitung view", e);
      model.addAttribute("error", "leitung.error.load");
    } catch (Exception e) {
      log.error("Failed to load Leitung view", e);
      model.addAttribute("error", "leitung.error.load");
    }
    model.addAttribute("selectedTab", TAB_GROUPS.equals(tab) ? TAB_GROUPS : TAB_MEMBERS);
    if (view != null) {
      model.addAttribute("selectedUnitId", selectedUnitId(view, unit));
      model.addAttribute("unitContext", unitContext());
      model.addAttribute("selfUserId", selfUserId(view));
      Map<UUID, List<OrgUnitMembershipDto>> rosters = new HashMap<>();
      Set<UUID> flagsKnown = new HashSet<>();
      for (LeitungUnitDto sk : units(view.specialCommands())) {
        rosters.put(sk.id(), skRoster(sk, flagsKnown));
      }
      model.addAttribute("skRosters", rosters);
      model.addAttribute("skFlagsKnown", flagsKnown);
    }
    if ("leitungSections".equals(fragment)) {
      return "organisation/leitung :: leitungSections";
    }
    return "organisation/leitung";
  }

  /**
   * Picks the unit the detail pane shows: the requested one when the view lists it, otherwise the
   * first unit in tree order (Organisationsleitung, Bereiche, Staffeln, Spezialkommandos).
   *
   * @param view the delegated view.
   * @param requested the {@code ?unit=} value; may be {@code null} or not a listed id.
   * @return the selected unit's id as a string, or {@code null} when the view lists no unit.
   */
  @Nullable
  private static String selectedUnitId(@NotNull LeitungViewDto view, @Nullable String requested) {
    List<LeitungUnitDto> all = allUnits(view);
    for (LeitungUnitDto u : all) {
      if (u.id() != null && u.id().toString().equals(requested)) {
        return requested;
      }
    }
    for (LeitungUnitDto u : all) {
      if (u.id() != null) {
        return u.id().toString();
      }
    }
    return null;
  }

  /**
   * Every unit of the view in tree order: Organisationsleitung, Bereiche, Staffeln,
   * Spezialkommandos.
   *
   * @param view the delegated view.
   * @return the non-null units, in tree order.
   */
  @NotNull
  private static List<LeitungUnitDto> allUnits(@NotNull LeitungViewDto view) {
    List<LeitungUnitDto> all = new ArrayList<>();
    all.addAll(units(view.organisationsleitungen()));
    all.addAll(units(view.bereiche()));
    all.addAll(units(view.squadrons()));
    all.addAll(units(view.specialCommands()));
    return all;
  }

  /**
   * Null-safe view of one of the view's unit lists, skipping {@code null} entries.
   *
   * @param list the list as decoded; may be {@code null}.
   * @return the non-null units, in order.
   */
  @NotNull
  private static List<LeitungUnitDto> units(@Nullable List<LeitungUnitDto> list) {
    if (list == null) {
      return List.of();
    }
    List<LeitungUnitDto> out = new ArrayList<>();
    for (LeitungUnitDto u : list) {
      if (u != null) {
        out.add(u);
      }
    }
    return out;
  }

  /**
   * The caller's own user id, read from the roster row the backend marks as {@code self}.
   *
   * @param view the delegated view.
   * @return the caller's id, or {@code null} when the caller holds no seat in a listed unit.
   */
  @Nullable
  private static UUID selfUserId(@NotNull LeitungViewDto view) {
    List<LeitungUnitDto> all = allUnits(view);
    for (LeitungUnitDto u : all) {
      if (u.members() == null) {
        continue;
      }
      for (LeitungMemberDto m : u.members()) {
        if (m != null && m.self()) {
          return m.userId();
        }
      }
    }
    return null;
  }

  /**
   * Reads the org chart to place each unit under its Bereich and department colour. The read is
   * best effort: the tree renders without colour and Bereich when it fails.
   *
   * @return the context per unit id; empty when the chart is unavailable.
   */
  @NotNull
  private Map<UUID, LeitungUnitContext> unitContext() {
    Map<UUID, LeitungUnitContext> context = new HashMap<>();
    OrgChartDto chart;
    try {
      chart = backendApiClient.get("/api/v1/org-chart", OrgChartDto.class);
    } catch (RuntimeException e) {
      log.debug("Org chart unavailable for the Leitung tree", e);
      return context;
    }
    if (chart == null || chart.bereiche() == null) {
      return context;
    }
    for (BereichChartDto b : chart.bereiche()) {
      if (b == null || b.orgUnitId() == null) {
        continue;
      }
      context.put(b.orgUnitId(), new LeitungUnitContext(null, b.department()));
      LeitungUnitContext child = new LeitungUnitContext(b.name(), b.department());
      if (b.squadrons() != null) {
        for (SquadronChartDto s : b.squadrons()) {
          if (s != null && s.orgUnitId() != null) {
            context.put(s.orgUnitId(), child);
          }
        }
      }
      if (b.specialCommands() != null) {
        for (SpecialCommandChartDto sc : b.specialCommands()) {
          if (sc != null && sc.orgUnitId() != null) {
            context.put(sc.orgUnitId(), child);
          }
        }
      }
    }
    return context;
  }

  /**
   * Builds a Spezialkommando's roster rows from the view, adding the Logistiker and Einsatzleiter
   * flags when the caller may manage the roster and the flag read succeeds.
   *
   * @param sk the Spezialkommando as the view lists it.
   * @param flagsKnown collects the ids of the SKs whose flags were read.
   * @return one row per member, in the view's order; never {@code null}.
   */
  @NotNull
  private List<OrgUnitMembershipDto> skRoster(
      @NotNull LeitungUnitDto sk, @NotNull Set<UUID> flagsKnown) {
    Map<UUID, Map<String, Object>> flags =
        sk.canManageRoster() && sk.id() != null ? skFlags(sk.id()) : null;
    if (flags != null) {
      flagsKnown.add(sk.id());
    }
    List<OrgUnitMembershipDto> rows = new ArrayList<>();
    if (sk.members() == null) {
      return rows;
    }
    for (LeitungMemberDto m : sk.members()) {
      if (m == null) {
        continue;
      }
      Map<String, Object> f = flags == null ? null : flags.get(m.userId());
      long version =
          f != null && f.get("version") != null
              ? MapPayloadValues.longOrZero(f.get("version"))
              : m.version();
      rows.add(
          new OrgUnitMembershipDto(
              m.userId(),
              m.userDisplayName(),
              sk.id(),
              OrgUnitKind.SPECIAL_COMMAND,
              f != null && MapPayloadValues.booleanOrFalse(f.get("isLogistician")),
              f != null && MapPayloadValues.booleanOrFalse(f.get("isMissionManager")),
              SK_LEAD.equals(m.role()),
              null,
              version));
    }
    return rows;
  }

  /**
   * Reads a Spezialkommando's member roster with its role flags.
   *
   * @param skId the Spezialkommando.
   * @return the raw rows keyed by user id, or {@code null} when the read fails or answers empty.
   */
  @Nullable
  private Map<UUID, Map<String, Object>> skFlags(@NotNull UUID skId) {
    List<Map<String, Object>> raw;
    try {
      raw = backendApiClient.get("/api/v1/special-commands/" + skId + "/members", MAP_LIST_TYPE);
    } catch (RuntimeException e) {
      log.debug("Spezialkommando roster flags unavailable", e);
      return null;
    }
    if (raw == null) {
      return null;
    }
    Map<UUID, Map<String, Object>> byUser = new HashMap<>();
    for (Map<String, Object> row : raw) {
      if (row == null) {
        continue;
      }
      UUID userId = MapPayloadValues.uuidOrNull(row.get("userId"));
      if (userId != null) {
        byUser.put(userId, row);
      }
    }
    return byUser;
  }

  /**
   * Assigns (or changes) a member's squadron leadership rank.
   *
   * @param squadronId the Staffel.
   * @param userId the member.
   * @param body the {@code {role, kommandoGroupId?, version}} payload.
   * @return 200 with the persisted membership, or the backend error status + body.
   */
  @PutMapping("/squadrons/{squadronId}/ranks/{userId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> assignSquadronRank(
      @PathVariable @NotNull UUID squadronId,
      @PathVariable @NotNull UUID userId,
      @RequestBody Map<String, Object> body) {
    return proxy(
        "Assign squadron rank failed",
        () ->
            backendApiClient.put(
                "/api/v1/squadrons/" + squadronId + "/ranks/" + userId, body, Object.class));
  }

  /**
   * Clears a member's squadron leadership rank back to a plain member.
   *
   * @param squadronId the Staffel.
   * @param userId the member.
   * @param version the optimistic-lock version the client last read.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/squadrons/{squadronId}/ranks/{userId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> removeSquadronRank(
      @PathVariable @NotNull UUID squadronId,
      @PathVariable @NotNull UUID userId,
      @RequestParam("version") long version) {
    return proxy(
        "Remove squadron rank failed",
        () ->
            backendApiClient.delete(
                "/api/v1/squadrons/" + squadronId + "/ranks/" + userId + "?version=" + version,
                Object.class));
  }

  /**
   * Creates a Kommandogruppe in the Staffel.
   *
   * @param squadronId the Staffel.
   * @param body the {@code {name}} payload.
   * @return 200 with the created group, or the backend error status + body.
   */
  @PostMapping("/squadrons/{squadronId}/kommando-groups/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> createKommandoGroup(
      @PathVariable @NotNull UUID squadronId, @RequestBody Map<String, Object> body) {
    return proxy(
        "Create Kommandogruppe failed",
        () ->
            backendApiClient.post(
                "/api/v1/squadrons/" + squadronId + "/kommando-groups", body, Object.class));
  }

  /**
   * Renames / reorders a Kommandogruppe.
   *
   * @param groupId the group.
   * @param body the {@code {name, sortIndex, version}} payload.
   * @return 200 with the updated group, or the backend error status + body.
   */
  @PutMapping("/kommando-groups/{groupId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> updateKommandoGroup(
      @PathVariable @NotNull UUID groupId, @RequestBody Map<String, Object> body) {
    return proxy(
        "Update Kommandogruppe failed",
        () -> backendApiClient.put("/api/v1/kommando-groups/" + groupId, body, Object.class));
  }

  /**
   * Deletes a Kommandogruppe.
   *
   * @param groupId the group.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/kommando-groups/{groupId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> deleteKommandoGroup(@PathVariable @NotNull UUID groupId) {
    return proxy(
        "Delete Kommandogruppe failed",
        () -> backendApiClient.delete("/api/v1/kommando-groups/" + groupId, Object.class));
  }

  /**
   * Adds a Bereich leadership member (Bereichsleiter / Koordinator / Operator).
   *
   * @param bereichId the Bereich.
   * @param body the {@code {userId, role}} payload.
   * @return 200 with the persisted membership, or the backend error status + body.
   */
  @PostMapping("/bereiche/{bereichId}/members/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> addBereichLeader(
      @PathVariable @NotNull UUID bereichId, @RequestBody Map<String, Object> body) {
    return proxy(
        "Add Bereich leader failed",
        () ->
            backendApiClient.post(
                "/api/v1/org-hierarchy/bereiche/" + bereichId + "/members", body, Object.class));
  }

  /**
   * Removes a Bereich leadership member.
   *
   * @param bereichId the Bereich.
   * @param userId the member.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/bereiche/{bereichId}/members/{userId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> removeBereichLeader(
      @PathVariable @NotNull UUID bereichId, @PathVariable @NotNull UUID userId) {
    return proxy(
        "Remove Bereich leader failed",
        () ->
            backendApiClient.delete(
                "/api/v1/org-hierarchy/bereiche/" + bereichId + "/members/" + userId,
                Object.class));
  }

  /**
   * Adds an Organisationsleitung member (admin-only at the backend).
   *
   * @param olId the OL.
   * @param body the {@code {userId}} payload.
   * @return 200 with the persisted membership, or the backend error status + body.
   */
  @PostMapping("/organisationsleitung/{olId}/members/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> addOlMember(
      @PathVariable @NotNull UUID olId, @RequestBody Map<String, Object> body) {
    return proxy(
        "Add OL member failed",
        () ->
            backendApiClient.post(
                "/api/v1/org-hierarchy/organisationsleitung/" + olId + "/members",
                body,
                Object.class));
  }

  /**
   * Removes an Organisationsleitung member.
   *
   * @param olId the OL.
   * @param userId the member.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/organisationsleitung/{olId}/members/{userId}/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> removeOlMember(
      @PathVariable @NotNull UUID olId, @PathVariable @NotNull UUID userId) {
    return proxy(
        "Remove OL member failed",
        () ->
            backendApiClient.delete(
                "/api/v1/org-hierarchy/organisationsleitung/" + olId + "/members/" + userId,
                Object.class));
  }

  /**
   * Designates the Grand Admiral of the Organisationsleitung (admin-only at the backend,
   * REQ-ORG-021).
   *
   * @param olId the OL.
   * @param body the {@code {userId}} payload.
   * @return 200 on success, or the backend error status + body.
   */
  @PutMapping("/organisationsleitung/{olId}/grand-admiral/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> setGrandAdmiral(
      @PathVariable @NotNull UUID olId, @RequestBody Map<String, Object> body) {
    return proxy(
        "Set Grand Admiral failed",
        () ->
            backendApiClient.put(
                "/api/v1/org-hierarchy/organisationsleitung/" + olId + "/grand-admiral",
                body,
                Object.class));
  }

  /**
   * Vacates the Grand Admiral post; the former holder stays an OL member.
   *
   * @param olId the OL.
   * @return 200 on success, or the backend error status + body.
   */
  @DeleteMapping("/organisationsleitung/{olId}/grand-admiral/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> removeGrandAdmiral(@PathVariable @NotNull UUID olId) {
    return proxy(
        "Remove Grand Admiral failed",
        () ->
            backendApiClient.delete(
                "/api/v1/org-hierarchy/organisationsleitung/" + olId + "/grand-admiral",
                Object.class));
  }

  /**
   * Toggles the SK-Leiter flag on a Spezialkommando member.
   *
   * @param skId the Spezialkommando.
   * @param userId the member.
   * @param body the {@code {isLead, version}} payload.
   * @return 200 with the persisted membership, or the backend error status + body.
   */
  @PatchMapping("/special-commands/{skId}/members/{userId}/lead/ajax")
  @ResponseBody
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public ResponseEntity<Object> toggleSkLead(
      @PathVariable @NotNull UUID skId,
      @PathVariable @NotNull UUID userId,
      @RequestBody Map<String, Object> body) {
    return proxy(
        "Toggle SK lead failed",
        () ->
            backendApiClient.patch(
                "/api/v1/special-commands/" + skId + "/members/" + userId + "/lead",
                body,
                Object.class));
  }

  /**
   * Runs a backend write call, returning its result as 200 and relaying any backend RFC-7807
   * failure as its original status + {@code {code, detail}} body (or a 500 for an unexpected
   * error).
   *
   * @param logMessage the log prefix for a failure.
   * @param call the backend call to run.
   * @return the proxied response.
   */
  private ResponseEntity<Object> proxy(String logMessage, BackendCall call) {
    try {
      Object result = call.run();
      return ResponseEntity.ok(result == null ? Map.of() : result);
    } catch (BackendServiceException e) {
      log.warn("{}: status={}, code={}", logMessage, e.getStatusCode(), e.getProblemCode());
      Map<String, Object> payload = new HashMap<>();
      payload.put("code", e.getProblemCode());
      payload.put("detail", e.getProblemDetail());
      int status = e.getStatusCode() > 0 ? e.getStatusCode() : 500;
      return ResponseEntity.status(status).body(payload);
    } catch (Exception e) {
      log.error(logMessage, e);
      Map<String, Object> payload = new HashMap<>();
      payload.put("code", "INTERNAL_ERROR");
      return ResponseEntity.status(500).body(payload);
    }
  }

  /** A backend write call that may throw a {@link BackendServiceException}. */
  @FunctionalInterface
  private interface BackendCall {
    /**
     * Runs the backend call.
     *
     * @return the backend result, possibly {@code null}.
     */
    Object run();
  }
}
