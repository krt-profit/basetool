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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Single seam that redacts mission DTOs before they leave the API to a <b>member below
 * Logistician</b> (REQ-SEC-007). Extracted verbatim from {@code MissionController} (audit L-tier
 * controller de-bloat) so the redaction rules live in one small, independently unit-testable class
 * instead of buried in the 2500-line controller, and so the later mission read/write controller
 * split can share the exact same pass.
 *
 * <p><b>One redaction level, since ADR-0159.</b> Owner and managers are cleared for a peer who is
 * only reading, and every nested user — each participant's, and each assigned unit's <b>ship
 * owner</b> (REQ-SEC-040) — stripped to the public callsign tuple. The caller's own edit/manage
 * flags are forwarded rather than forced off, and the management pair travels with them; see {@link
 * #cleanupMissionForPeer}. Payout preference and the free-text comment are kept, because a peer is
 * a member of the organisation.
 *
 * <p>There used to be a second, stricter <b>outsider</b> tier for anonymous and role-less callers
 * (ADR-0034, superseded), which additionally hid the description and each participant's payout
 * preference and comment. Both audiences are gone: there is no anonymous mission read, and no role
 * below member. The class was named {@code MissionGuestRedactor} and its methods {@code
 * cleanup…ForGuest} for that audience; keeping those names would have described a caller the system
 * can no longer produce.
 *
 * <p><b>Why the explicit full-field {@code new MissionDto(...)} reconstruction is deliberate — do
 * not "simplify" it into pass-through withers.</b> Every field the
 * mission/participant/unit/ship/user records carry is listed out here, so adding a field to any of
 * those records is a compile error until a human decides, field by field, whether a peer may see
 * it. That compiler-enforced exhaustiveness is the load-bearing safety net that prevents a newly
 * added sensitive field from silently leaking to a peer; a wither-based redactor ({@code
 * dto.withOwner(null)}) would default a new field to <em>pass through</em>, which is the dangerous
 * default for a redactor. Keep the reconstruction explicit.
 *
 * <p><b>The exhaustiveness only holds for records this class actually descends into.</b> A nested
 * collection forwarded by reference is a hole in it, because the compiler has nothing to check:
 * that is precisely how {@code assignedUnits[].ship.owner} shipped a full {@link UserDto} — roles,
 * permissions, memberships — to anonymous callers of the public mission detail while every
 * surrounding control stayed green (REQ-SEC-040). When a new nested record reaches a user, a
 * squadron or any free text, give it its own {@code cleanup…ForPeer} pass here rather than
 * forwarding the collection.
 *
 * <p>The {@code cleanup&hellip;ForPeer} method names are load-bearing too: the {@code
 * peerReadableMissionEndpointsMustRedactPii} ArchUnit rule recognises a redaction call by matching
 * this naming convention in the endpoint's own body (regardless of the declaring class), so the
 * extraction keeps the guard green as long as endpoints keep calling these methods by name.
 */
@Component
public class MissionPeerRedactor {

  /**
   * Redacts a mission DTO for a peer: hides owner and managers from a reader, keeps the caller's
   * own capability flags, and recursively cleans each participant. The mission economy (inventory /
   * refinery orders) is no longer part of this DTO (#1138) — it is member-gated at its own
   * endpoints — so there is nothing to strip here for it. This is the only path that controls what
   * leaves the API to a member below Logistician — never lift data into the controller layer
   * without thinking about this method first.
   *
   * <p><b>The management pair is kept for a caller who may manage the mission.</b> Since ADR-0159
   * this pass runs for every caller below Logistician, and a bare {@code MISSION_MANAGER} is one —
   * so the response used to assert {@code canManageManagers: true} and hand over an empty manager
   * list in the same breath. The detail view then rendered the Verwaltung panel with the owner as
   * {@code -} and no co-manager chips, and its add/remove controls operated on a list the manager
   * could not see. Nothing is disclosed by closing that: {@link UserReferenceDto} is id, username,
   * display name, effective name and rank — the same public callsign tuple every participant on the
   * same mission already carries through {@link #cleanupUserForPeer}, and it goes only to a caller
   * who may rewrite the very list it names. A peer who is only reading still sees neither.
   *
   * @param dto the full mission DTO
   * @return a redacted copy safe for a member below Logistician
   */
  public MissionDto cleanupMissionForPeer(MissionDto dto) {
    // Both flags, not just canManageManagers: the Verwaltung tab opens on either
    // (mission-detail.html), and the owner field it shows is the one canEdit lets a caller change.
    boolean managing =
        Boolean.TRUE.equals(dto.canEdit()) || Boolean.TRUE.equals(dto.canManageManagers());

    Set<MissionParticipantDto> cleanedParticipants =
        dto.participants() == null
            ? null
            : dto.participants().stream()
                .map(this::cleanupParticipantForPeer)
                .collect(Collectors.toSet());

    List<MissionUnitDto> cleanedUnits =
        dto.assignedUnits() == null
            ? null
            : dto.assignedUnits().stream().map(this::cleanupUnitForPeer).toList();

    return new MissionDto(
        dto.id(),
        dto.name(),
        dto.description(),
        dto.calendarLink(),
        dto.status(),
        dto.meetingTime(),
        dto.plannedStartTime(),
        dto.actualStartTime(),
        dto.plannedEndTime(),
        dto.actualEndTime(),
        dto.isInternal(),
        cleanedParticipants,
        cleanedUnits,
        dto.frequencies(),
        dto.operation(),
        // Owner and managers stay hidden from a peer who is only reading — but not from one the
        // very same response tells it may CHANGE them. See managesThisMission below.
        managing ? dto.owner() : null,
        managing ? dto.managers() : null,
        // canEdit / canManageManagers are answers ABOUT THE CALLER, computed per request by
        // MissionMapper from their own authorities — not somebody else's data, and telling a
        // caller what they may do cannot disclose anything they do not already have. They were
        // forced to false while this pass only ever ran for outsiders, for whom the answer was
        // false anyway. Since ADR-0159 the pass runs for every caller below Logistician, and a
        // MISSION_MANAGER is one: the hierarchy declares ADMIN/OFFICER above both roles but never
        // MISSION_MANAGER above LOGISTICIAN. Forcing them off would hide the management controls
        // from the person who owns the Einsatz.
        dto.canEdit(),
        dto.canManageManagers(),
        dto.version(),
        dto.coreVersion(),
        dto.scheduleVersion(),
        dto.flagsVersion(),
        dto.checkedInParticipants(),
        dto.registeredParticipants(),
        // Squadron shorthand is not sensitive (MULTI_SQUADRON_PLAN.md section 7) — forwarded so
        // the detail view shows the owning-squadron badge.
        dto.owningSquadron(),
        dto.owningOrgUnitVersion(),
        // Party lead is a public leadership designation (like the Führungspositionen list) and the
        // UserReferenceDto carries only the callsign tuple
        // (username/displayName/effectiveName/rank)
        // — no email or real name — so it is forwarded unchanged.
        dto.partyLeadUser(),
        dto.partyLeadGuestName(),
        dto.partyLeadVersion(),
        // Ablauf steps, goals (Ziele) and meeting point (Treffpunkt) are non-PII mission
        // planning data — forwarded like the assigned units and frequencies. So is the long
        // Markdown description above: it was the one field the outsider tier hid, and with that
        // tier gone (ADR-0159) a peer is a member of the organisation and reads it.
        dto.steps(),
        dto.stepsVersion(),
        dto.objectives(),
        dto.objectivesVersion(),
        dto.meetingPoint());
  }

  /**
   * Redacts one assigned unit for a peer by cleaning the nested {@link ShipDto}.
   *
   * <p>The unit itself is mission planning data a peer may see (REQ-SEC-007) — name, ship type,
   * frequency, note, crew and the {@code responsibleUser}, which is a PII-free {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto} callsign tuple. Its {@code
   * ship}, however, carries a full {@link UserDto} owner, which is why this pass exists at all
   * (REQ-SEC-040).
   *
   * @param dto the unit DTO straight from the mapper; never {@code null}.
   * @return a copy whose ship owner is reduced to the public callsign tuple.
   */
  public MissionUnitDto cleanupUnitForPeer(MissionUnitDto dto) {
    return new MissionUnitDto(
        dto.id(),
        dto.name(),
        dto.shipType(),
        dto.ship() == null ? null : cleanupShipForPeer(dto.ship()),
        dto.frequency(),
        dto.highValueUnit(),
        // UserReferenceDto — the public callsign tuple only, same rationale as partyLeadUser.
        dto.responsibleUser(),
        dto.note(),
        dto.version(),
        dto.crew());
  }

  /**
   * Redacts a ship DTO for a peer: routes the nested {@code owner} through {@link
   * #cleanupUserForPeer} so it leaves the API as the public callsign tuple instead of a full member
   * record.
   *
   * <p><b>REQ-SEC-040 — why this exists.</b> {@code Ship.owner} is {@code nullable = false}, so an
   * assigned ship <em>always</em> carries an owner, and {@code UserMapper.toDto} nulls only {@code
   * email}. Before this pass, {@code assignedUnits[].ship.owner} therefore handed an
   * <em>unauthenticated</em> caller of the public mission detail the owner's roles and permissions
   * (i.e. who holds ADMIN/OFFICER), free-text description, org-unit memberships, join date and
   * Discord-link status — the exact field set {@link #cleanupUserForPeer} exists to strip, reached
   * through a path no redactor descended into.
   *
   * @param dto the ship DTO nested in an assigned unit; never {@code null}.
   * @return a copy whose owner is redacted for a peer.
   */
  public ShipDto cleanupShipForPeer(ShipDto dto) {
    return new ShipDto(
        dto.id(),
        dto.name(),
        dto.shipType(),
        dto.insurance(),
        dto.location(),
        dto.fitted(),
        dto.owner() == null ? null : cleanupUserForPeer(dto.owner()),
        // Squadron shorthand is not sensitive — same call as the mission's own owningSquadron.
        dto.owningSquadron(),
        dto.version());
  }

  /**
   * Redacts a participant DTO for a peer: cleans the nested user via {@link #cleanupUserForPeer},
   * keeps the displayed fields (org units, job-type, comment, payout preference, times) intact
   * because those are public per the squadron policy.
   *
   * @param dto the participant DTO
   * @return a redacted copy safe for a member below Logistician
   */
  public MissionParticipantDto cleanupParticipantForPeer(MissionParticipantDto dto) {
    UserDto cleanedUser = dto.user() != null ? cleanupUserForPeer(dto.user()) : null;
    return new MissionParticipantDto(
        dto.id(),
        cleanedUser,
        dto.guestName(),
        dto.orgUnits(),
        dto.desiredMissionJobType(),
        dto.plannedMissionJobType(),
        dto.comment(),
        dto.startTime(),
        dto.endTime(),
        dto.payoutPreference(),
        dto.version());
  }

  /**
   * Redacts a user DTO for a peer: drops email, description, roles, permissions, announcement
   * watermark and join date. Username + displayName + rank remain visible because those are the
   * public callsign tuple. Shared verbatim with the finance ledger's peer-PII pass ({@code
   * MissionFinanceEntryController} delegates its participant-user redaction here, audit H-1) so the
   * redacted user shape is identical across the mission and finance views.
   *
   * <p><b>It is NOT identical to {@code UserDtoRedaction.toPeerShape}</b>, the job-order surface's
   * peer projection of the same record: that one forwards {@code squadron} and {@code squadrons}
   * where this one nulls them. Noticed in the 2026-09-06 review and left as it stands rather than
   * reconciled by guess — changing either direction is a visibility decision, not a cleanup, and
   * the mission surface already shows a participant's affiliations through {@code
   * MissionParticipantDto.orgUnits} anyway. Recorded here so the next reader meets the difference
   * as a known one instead of assuming the two are interchangeable.
   *
   * @param dto the user DTO
   * @return a redacted copy safe for a member below Logistician
   */
  public UserDto cleanupUserForPeer(UserDto dto) {
    return new UserDto(
        dto.id(),
        dto.username(),
        dto.displayName(),
        dto.effectiveName(),
        null, // email
        dto.rank(),
        null, // description
        null, // roles
        null, // permissions
        null, // lastReadAnnouncementId
        false, // isLogistician
        false, // isMissionManager
        dto.inKeycloak(),
        null, // squadron – not exposed to a peer
        null, // squadrons – not exposed to a peer
        dto.version(),
        null, // joinDate – not exposed to a peer
        null // discordLinked – not exposed to a peer
        );
  }
}
