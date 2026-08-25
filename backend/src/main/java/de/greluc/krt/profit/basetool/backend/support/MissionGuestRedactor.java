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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Single seam that redacts mission DTOs before they leave the API to a caller who is not a squadron
 * member — anonymous callers and authenticated but role-less {@code GUEST} accounts (see {@code
 * AuthHelperService#isMemberOrAbove()}). Extracted verbatim from {@code MissionController} (audit
 * L-tier controller de-bloat) so the redaction rules live in one small, independently unit-testable
 * class instead of buried in the 2500-line controller, and so the later mission read/write
 * controller split can share the exact same pass.
 *
 * <p>Two redaction levels, matching the two audiences (REQ-SEC-021, ADR-0034):
 *
 * <ul>
 *   <li>{@link #cleanupMissionForGuest(MissionDto)} — the <b>member-peer</b> level: owner/managers
 *       cleared, edit/manage flags forced off, and every nested user — each participant's, and each
 *       assigned unit's <b>ship owner</b> (REQ-SEC-040) — stripped to the public callsign tuple.
 *       Payout preference and the free-text comment are kept.
 *   <li>{@link #cleanupOutsiderMissionForGuest(MissionDto)} — the strict <b>outsider</b> level: the
 *       member-peer pass plus the free-text description hidden and each participant's {@code
 *       payoutPreference} + {@code comment} nulled.
 * </ul>
 *
 * <p><b>Why the explicit full-field {@code new MissionDto(...)} reconstruction is deliberate — do
 * not "simplify" it into pass-through withers.</b> Every field the
 * mission/participant/unit/ship/user records carry is listed out here, so adding a field to any of
 * those records is a compile error until a human decides, field by field, whether a guest may see
 * it. That compiler-enforced exhaustiveness is the load-bearing safety net that prevents a newly
 * added sensitive field from silently leaking to guests; a wither-based redactor ({@code
 * dto.withOwner(null)}) would default a new field to <em>pass through</em>, which is the dangerous
 * default for a redactor. Keep the reconstruction explicit.
 *
 * <p><b>The exhaustiveness only holds for records this class actually descends into.</b> A nested
 * collection forwarded by reference is a hole in it, because the compiler has nothing to check:
 * that is precisely how {@code assignedUnits[].ship.owner} shipped a full {@link UserDto} — roles,
 * permissions, memberships — to anonymous callers of the public mission detail while every
 * surrounding control stayed green (REQ-SEC-040). When a new nested record reaches a user, a
 * squadron or any free text, give it its own {@code cleanup…ForGuest} pass here rather than
 * forwarding the collection.
 *
 * <p>The {@code cleanup&hellip;ForGuest} method names are load-bearing too: the {@code
 * anonymousReadableMissionEndpointsMustRedactGuestPii} ArchUnit rule recognises a redaction call by
 * matching this naming convention in a guest-reachable endpoint's own body (regardless of the
 * declaring class), so the extraction keeps the guard green as long as endpoints keep calling these
 * methods by name.
 */
@Component
public class MissionGuestRedactor {

  /**
   * Redacts a mission DTO for an anonymous viewer: strips owner/managers, clears edit/manage flags,
   * and recursively cleans each participant. The mission economy (inventory / refinery orders) is
   * no longer part of this DTO (#1138) — it is member-gated at its own endpoints — so there is
   * nothing to strip here for it. This is the only path that controls what leaves the API for
   * guests — never lift data into the controller layer without thinking about this method first.
   *
   * @param dto the full mission DTO
   * @return a redacted copy safe for unauthenticated callers
   */
  public MissionDto cleanupMissionForGuest(MissionDto dto) {
    Set<MissionParticipantDto> cleanedParticipants =
        dto.participants() == null
            ? null
            : dto.participants().stream()
                .map(this::cleanupParticipantForGuest)
                .collect(Collectors.toSet());

    List<MissionUnitDto> cleanedUnits =
        dto.assignedUnits() == null
            ? null
            : dto.assignedUnits().stream().map(this::cleanupUnitForGuest).toList();

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
        null, // owner
        null, // managers
        false, // canEdit
        false, // canManageManagers
        dto.version(),
        dto.coreVersion(),
        dto.scheduleVersion(),
        dto.flagsVersion(),
        dto.checkedInParticipants(),
        dto.registeredParticipants(),
        // Squadron shorthand is not sensitive (MULTI_SQUADRON_PLAN.md section 7) — forward
        // through to guests so the public detail view shows the owning-squadron badge.
        dto.owningSquadron(),
        dto.owningOrgUnitVersion(),
        // Party lead is a public leadership designation (like the Führungspositionen list) and the
        // UserReferenceDto carries only the callsign tuple
        // (username/displayName/effectiveName/rank)
        // — no email or real name — so it is forwarded to guests unchanged.
        dto.partyLeadUser(),
        dto.partyLeadGuestName(),
        dto.partyLeadVersion(),
        // Ablauf steps, goals (Ziele) and meeting point (Treffpunkt) are non-PII mission
        // planning data — forwarded like the assigned units and frequencies (the long Markdown
        // description stays the one free-text field hidden from outsiders, handled below).
        dto.steps(),
        dto.stepsVersion(),
        dto.objectives(),
        dto.objectivesVersion(),
        dto.meetingPoint());
  }

  /**
   * Redaction for mission "outsiders" — anonymous callers AND authenticated but role-less {@code
   * GUEST} accounts (see {@code AuthHelperService#isMemberOrAbove()}). It applies the member-peer
   * {@link #cleanupMissionForGuest} pass (owner / managers cleared, participant PII stripped to the
   * public callsign tuple) and additionally hides only the free-text <b>description</b>.
   *
   * <p>By explicit product decision an outsider <b>does</b> see — on a non-internal mission — the
   * owning <b>organisation</b> ({@code owningSquadron}), the <b>participant roster</b> with each
   * participant's <b>payout preference</b> (PII removed by the peer pass), the assigned
   * <b>units</b> and the mission <b>frequencies</b>. The only things kept from an outsider beyond
   * the member-peer redaction are the description (here) and the finance ledger (the {@code
   * /finance-entries} endpoints stay member-only — they are a separate surface, not part of this
   * DTO). The mission economy (inventory / refinery orders) is no longer part of this DTO (#1138)
   * and is member-gated at its own endpoints, so it is never on the outsider surface at all. The
   * {@code ForGuest} suffix + the delegated {@link #cleanupMissionForGuest} call satisfy the {@code
   * anonymousReadableMissionEndpointsMustRedactGuestPii} ArchUnit rule.
   *
   * @param dto the full mission DTO straight from the mapper
   * @return a copy with participant PII + owner/managers stripped and the description hidden, but
   *     organisation, roster, units, frequencies and payout preference kept
   */
  public MissionDto cleanupOutsiderMissionForGuest(MissionDto dto) {
    MissionDto peer = cleanupMissionForGuest(dto);

    // ADR-0034: the anonymous outsider view drops each participant's payoutPreference + free-text
    // comment (kept on the member-peer view). The peer roster is already PII-stripped; strip these
    // two fields here so getMissionById and the participant endpoints (which all redact through
    // this
    // method for outsiders) never expose them to unauthenticated callers.
    Set<MissionParticipantDto> outsiderParticipants =
        peer.participants() == null
            ? null
            : peer.participants().stream()
                .map(this::stripOutsiderParticipantFields)
                .collect(Collectors.toSet());

    return new MissionDto(
        peer.id(),
        peer.name(),
        null, // description — the only field hidden on top of the member-peer redaction
        peer.calendarLink(),
        peer.status(),
        peer.meetingTime(),
        peer.plannedStartTime(),
        peer.actualStartTime(),
        peer.plannedEndTime(),
        peer.actualEndTime(),
        peer.isInternal(),
        outsiderParticipants, // roster kept, but payout + comment stripped (ADR-0034)
        peer.assignedUnits(), // units kept — each unit's ship owner already redacted by the peer
        // pass
        peer.frequencies(), // frequencies kept
        peer.operation(),
        peer.owner(), // already null
        peer.managers(), // already null
        peer.canEdit(), // already false
        peer.canManageManagers(), // already false
        peer.version(),
        peer.coreVersion(),
        peer.scheduleVersion(),
        peer.flagsVersion(),
        peer.checkedInParticipants(),
        peer.registeredParticipants(),
        peer.owningSquadron(), // organisation kept
        peer.owningOrgUnitVersion(),
        peer.partyLeadUser(),
        peer.partyLeadGuestName(),
        peer.partyLeadVersion(),
        peer.steps(), // Ablauf kept (planning data, like units/frequencies)
        peer.stepsVersion(),
        peer.objectives(), // goals kept; long description is the hidden free-text field
        peer.objectivesVersion(),
        peer.meetingPoint());
  }

  /**
   * Redacts one assigned unit for guests by cleaning the nested {@link ShipDto}.
   *
   * <p>The unit itself is mission planning data an outsider may see (REQ-SEC-021) — name, ship
   * type, frequency, note, crew and the {@code responsibleUser}, which is a PII-free {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto} callsign tuple. Its {@code
   * ship}, however, carries a full {@link UserDto} owner, which is why this pass exists at all
   * (REQ-SEC-040).
   *
   * @param dto the unit DTO straight from the mapper; never {@code null}.
   * @return a copy whose ship owner is reduced to the public callsign tuple.
   */
  public MissionUnitDto cleanupUnitForGuest(MissionUnitDto dto) {
    return new MissionUnitDto(
        dto.id(),
        dto.name(),
        dto.shipType(),
        dto.ship() == null ? null : cleanupShipForGuest(dto.ship()),
        dto.frequency(),
        dto.highValueUnit(),
        // UserReferenceDto — the public callsign tuple only, same rationale as partyLeadUser.
        dto.responsibleUser(),
        dto.note(),
        dto.version(),
        dto.crew());
  }

  /**
   * Redacts a ship DTO for guests: routes the nested {@code owner} through {@link
   * #cleanupUserForGuest} so it leaves the API as the public callsign tuple instead of a full
   * member record.
   *
   * <p><b>REQ-SEC-040 — why this exists.</b> {@code Ship.owner} is {@code nullable = false}, so an
   * assigned ship <em>always</em> carries an owner, and {@code UserMapper.toDto} nulls only {@code
   * email}. Before this pass, {@code assignedUnits[].ship.owner} therefore handed an
   * <em>unauthenticated</em> caller of the public mission detail the owner's roles and permissions
   * (i.e. who holds ADMIN/OFFICER), free-text description, org-unit memberships, join date and
   * Discord-link status — the exact field set {@link #cleanupUserForGuest} exists to strip, reached
   * through a path no redactor descended into.
   *
   * @param dto the ship DTO nested in an assigned unit; never {@code null}.
   * @return a copy whose owner is redacted for guests.
   */
  public ShipDto cleanupShipForGuest(ShipDto dto) {
    return new ShipDto(
        dto.id(),
        dto.name(),
        dto.shipType(),
        dto.insurance(),
        dto.location(),
        dto.fitted(),
        dto.owner() == null ? null : cleanupUserForGuest(dto.owner()),
        // Squadron shorthand is not sensitive — same call as the mission's own owningSquadron.
        dto.owningSquadron(),
        dto.version());
  }

  /**
   * Redacts a participant DTO for guests: cleans the nested user via {@link #cleanupUserForGuest},
   * keeps the displayed fields (org units, job-type, comment, payout preference, times) intact
   * because those are public per the squadron policy.
   *
   * @param dto the participant DTO
   * @return a redacted copy safe for unauthenticated callers
   */
  public MissionParticipantDto cleanupParticipantForGuest(MissionParticipantDto dto) {
    UserDto cleanedUser = dto.user() != null ? cleanupUserForGuest(dto.user()) : null;
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
        dto.version(),
        // M1: preserve the per-row capability token so the anonymous guest-sign-up CREATE response
        // still hands the creator their edit token. It is non-null only on that create response
        // (transient on the freshly persisted entity) and null on every read/edit, so redacting it
        // here would break the legitimate self-edit UX without adding any protection.
        dto.guestEditToken());
  }

  /**
   * ADR-0034: removes the two fields the anonymous outsider mission view does not expose — the
   * per-participant {@code payoutPreference} (financial intent) and the free-text {@code comment}
   * (uncontrolled text, possible incidental PII). Applied ONLY on the strict outsider paths ({@link
   * #cleanupOutsiderMissionForGuest} and the {@code addParticipantSlim} outsider branch); the
   * shared {@link #cleanupParticipantForGuest} deliberately keeps both fields so the authenticated
   * member-peer view is unchanged (REQ-SEC-021). Every other field — including the M1 {@code
   * guestEditToken} — passes through untouched.
   *
   * @param dto an already-PII-redacted participant DTO from the guest cleanup pass
   * @return a copy with {@code payoutPreference} and {@code comment} nulled
   */
  public MissionParticipantDto stripOutsiderParticipantFields(MissionParticipantDto dto) {
    return new MissionParticipantDto(
        dto.id(),
        dto.user(),
        dto.guestName(),
        dto.orgUnits(),
        dto.desiredMissionJobType(),
        dto.plannedMissionJobType(),
        null, // comment — ADR-0034: not exposed to anonymous outsiders
        dto.startTime(),
        dto.endTime(),
        null, // payoutPreference — ADR-0034: not exposed to anonymous outsiders
        dto.version(),
        dto.guestEditToken());
  }

  /**
   * Redacts a user DTO for guests: drops email, description, roles, permissions, announcement
   * watermark and join date. Username + displayName + rank remain visible because those are the
   * public callsign tuple. Shared verbatim with the finance ledger's peer-PII pass ({@code
   * MissionFinanceEntryController} delegates its participant-user redaction here, audit H-1) so the
   * redacted user shape is identical across the mission and finance views.
   *
   * @param dto the user DTO
   * @return a redacted copy safe for unauthenticated callers
   */
  public UserDto cleanupUserForGuest(UserDto dto) {
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
        null, // squadron – not exposed to guests
        null, // squadrons – not exposed to guests
        dto.version(),
        null, // joinDate – not exposed to guests
        null // discordLinked – not exposed to guests
        );
  }
}
