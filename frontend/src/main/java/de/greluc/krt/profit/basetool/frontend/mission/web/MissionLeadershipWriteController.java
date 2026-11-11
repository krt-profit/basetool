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

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.propagateBackendError;
import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;
import static de.greluc.krt.profit.basetool.frontend.mission.web.MissionWriteValues.textOrNull;
import static de.greluc.krt.profit.basetool.frontend.mission.web.MissionWriteValues.uuidOrNull;
import static de.greluc.krt.profit.basetool.frontend.mission.web.MissionWriteValues.versionOrZero;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.SetPartyLeadRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateMissionOwnerRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateMissionOwningOrgUnitRequest;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Handles mission ownership, managers and party leadership. */
@Controller
@UsesLayoutModel
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MissionLeadershipWriteController {

  private final MissionBackendClient missionClient;

  /**
   * Assigns or clears a mission's party lead (Partyleiter) from a form post, then reloads the page.
   *
   * <p>A picked member arrives as {@code userId}; free text arrives as {@code guestName} and is
   * resolved server-side. An empty submission clears the party lead.
   *
   * @param id mission id
   * @param userId resolved registered-user id from the autocomplete, or {@code null}
   * @param guestName free-text party-lead handle, or {@code null}
   * @param version expected {@code partyLeadVersion} echoed back from the rendered page
   * @param redirectAttributes flash-scoped toast carrier
   * @return redirect to {@code /missions/{id}}
   */
  @NotNull
  @PostMapping("/{id}/party-lead")
  public String setPartyLead(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) UUID userId,
      @RequestParam(required = false) String guestName,
      @RequestParam(required = false) Long version,
      RedirectAttributes redirectAttributes) {
    try {
      missionClient.setPartyLead(
          id,
          new SetPartyLeadRequest(
              userId,
              guestName != null && !guestName.isBlank() ? guestName : null,
              version != null ? version : 0L));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Set party lead failed with status {}: {}", e.getStatusCode(), e.getMessage());
      String toastKey =
          (e.getStatusCode() == 409)
              ? "error.mission.party_lead.conflict"
              : "error.mission.party_lead.update";
      redirectAttributes.addFlashAttribute("errorToast", toastKey);
    }
    return "redirect:/missions/" + id;
  }

  /**
   * AJAX variant of {@link #setPartyLead}, returning the refreshed mission as JSON; a 409 is
   * relayed as RFC 7807.
   *
   * @param id mission id (path)
   * @param body party-lead JSON ({@code userId} and/or {@code guestName}, plus {@code version}); an
   *     empty {@code userId} and {@code guestName} clear the lead
   * @return {@code 200} with the refreshed mission, {@code 400} for a malformed {@code userId} or
   *     {@code version}, or the upstream RFC 7807 error passed through
   */
  @PutMapping(value = "/{id}/party-lead/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> setPartyLeadAjax(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "set party lead (ajax) for mission " + id,
        () -> {
          SetPartyLeadRequest request;
          try {
            request =
                new SetPartyLeadRequest(
                    uuidOrNull(body.get("userId")),
                    textOrNull(body.get("guestName")),
                    versionOrZero(body.get("version")));
          } catch (IllegalArgumentException _) {
            log.debug("Party lead change refused: malformed userId or version");
            return ResponseEntity.badRequest().build();
          }
          missionClient.setPartyLead(id, request);
          MissionDto mission = missionClient.mission(id);
          return ResponseEntity.ok(mission);
        });
  }

  /**
   * AJAX endpoint that adds a co-manager to a mission. Co-managers can edit the mission like the
   * owner; the owner cannot be removed via this endpoint.
   *
   * @return 200 on success, propagated backend status on failure
   */
  @PostMapping("/{id}/managers/{userId}")
  @ResponseBody
  public ResponseEntity<Object> addManager(@PathVariable String id, @PathVariable String userId) {
    log.debug("START addManager - id: '{}', userId: '{}'", id, userId);
    if (id == null || id.isBlank() || userId == null || userId.isBlank()) {
      log.debug("MISSING PARAMETERS - id: '{}', userId: '{}'", id, userId);
      return ResponseEntity.badRequest().build();
    }
    UUID missionUuid;
    UUID userUuid;
    try {
      missionUuid = UUID.fromString(id.trim());
    } catch (IllegalArgumentException e) {
      log.debug("INVALID MISSION ID FORMAT - id: '{}', Error: {}", id, e.getMessage());
      return ResponseEntity.badRequest().build();
    }
    try {
      userUuid = UUID.fromString(userId.trim());
    } catch (IllegalArgumentException e) {
      log.debug("INVALID USER ID FORMAT - userId: '{}', Error: {}", userId, e.getMessage());
      return ResponseEntity.badRequest().build();
    }

    log.debug("CALLING BACKEND - Mission: {}, User: {}", missionUuid, userUuid);
    try {
      missionClient.addManager(missionUuid, userUuid);
      log.debug("SUCCESS - Manager {} added to mission {}", userUuid, missionUuid);
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      log.debug(
          "BACKEND ERROR adding manager for mission {} and user {}: Status={}, Message={},"
              + " Readable={}",
          missionUuid,
          userUuid,
          e.getStatusCode(),
          e.getMessage(),
          e.getReadableErrorMessage());
      return propagateBackendError(e);
    }
  }

  /**
   * AJAX endpoint that removes a co-manager. The owner is protected — removing the owner requires
   * {@link #setMissionOwner} instead.
   *
   * @return 200 on success, propagated backend status on failure
   */
  @DeleteMapping("/{id}/managers/{userId}")
  @ResponseBody
  public ResponseEntity<Object> removeManager(
      @PathVariable String id, @PathVariable String userId) {
    log.debug("START removeManager - id: '{}', userId: '{}'", id, userId);
    return relay(
        log,
        "remove manager " + userId + " from mission " + id,
        () -> {
          if (id == null || id.isBlank() || userId == null || userId.isBlank()) {
            log.debug("MISSING PARAMETERS in removeManager - id: '{}', userId: '{}'", id, userId);
            return ResponseEntity.badRequest().build();
          }
          UUID missionUuid;
          UUID userUuid;
          try {
            missionUuid = UUID.fromString(id.trim());
          } catch (IllegalArgumentException e) {
            log.debug(
                "INVALID MISSION ID FORMAT in removeManager - id: '{}', Error: {}",
                id,
                e.getMessage());
            return ResponseEntity.badRequest().build();
          }
          try {
            userUuid = UUID.fromString(userId.trim());
          } catch (IllegalArgumentException e) {
            log.debug(
                "INVALID USER ID FORMAT in removeManager - userId: '{}', Error: {}",
                userId,
                e.getMessage());
            return ResponseEntity.badRequest().build();
          }

          log.debug("CALLING BACKEND DELETE - Mission: {}, User: {}", missionUuid, userUuid);
          missionClient.removeManager(missionUuid, userUuid);
          log.debug("SUCCESS DELETE - Manager {} removed from mission {}", userUuid, missionUuid);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Hands a mission to another owner via {@code PUT /api/v1/missions/{id}/owner}, checked against
   * its {@code ownershipVersion}, and returns the refreshed mission.
   *
   * <p>The previous owner is not added to the co-managers. A stale version is relayed as the
   * backend's {@code 409}.
   *
   * @param id mission id (path)
   * @param body owner-change JSON: {@code userId} (a UUID string) plus {@code version}
   * @return {@code 200} with the refreshed mission, {@code 400} for a missing or malformed {@code
   *     userId} or a malformed {@code version}, or the upstream RFC 7807 error passed through
   */
  @PutMapping(value = "/{id}/owner/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> setMissionOwner(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    UpdateMissionOwnerRequest request;
    try {
      Object raw = body.get("userId");
      if (raw == null || String.valueOf(raw).isBlank()) {
        return ResponseEntity.badRequest().build();
      }
      request =
          new UpdateMissionOwnerRequest(
              UUID.fromString(String.valueOf(raw).trim()), versionOrZero(body.get("version")));
    } catch (IllegalArgumentException _) {
      log.debug("Owner change refused: userId is not a UUID or version is not a number");
      return ResponseEntity.badRequest().build();
    }
    try {
      missionClient.setOwner(id, request);
      MissionDto mission = missionClient.mission(id);
      return ResponseEntity.ok(mission);
    } catch (BackendServiceException e) {
      if (e.getStatusCode() == 409) {
        log.debug("Owner change for mission {} conflicted with a concurrent change", id);
        return propagateBackendError(e);
      }
      log.debug("Owner change (AJAX) failed: status={}", e.getStatusCode());
      return propagateBackendError(e);
    }
  }

  /**
   * Reassigns a mission's owning org unit, checked against its {@code owningOrgUnitVersion}
   * (REQ-ORG-018).
   *
   * @param id mission id (path)
   * @param body reassignment JSON: {@code owningOrgUnitId} (a UUID string, or blank/absent for
   *     ownerless) plus {@code version}
   * @return {@code 200} on success, {@code 400} for a malformed {@code owningOrgUnitId} or {@code
   *     version}, or the upstream RFC 7807 error passed through
   */
  @PutMapping(value = "/{id}/owning-org-unit/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> setMissionOwningOrgUnit(
      @PathVariable @NotNull UUID id, @RequestBody Map<String, Object> body) {
    return relay(
        log,
        "set mission owning org unit for mission " + id,
        () -> {
          UpdateMissionOwningOrgUnitRequest request;
          try {
            request =
                new UpdateMissionOwningOrgUnitRequest(
                    uuidOrNull(body.get("owningOrgUnitId")), versionOrZero(body.get("version")));
          } catch (IllegalArgumentException _) {
            log.debug("Owning org unit change refused: malformed owningOrgUnitId or version");
            return ResponseEntity.badRequest().build();
          }
          missionClient.setOwningOrgUnit(id, request);
          MissionDto mission = missionClient.mission(id);
          return ResponseEntity.ok(mission);
        });
  }
}
