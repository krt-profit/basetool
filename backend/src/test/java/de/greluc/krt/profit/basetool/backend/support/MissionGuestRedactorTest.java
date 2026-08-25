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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionStepDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MissionGuestRedactor}, pinning exactly which fields each redaction level
 * nulls versus forwards. These are the security contract for what leaves the API to a guest
 * (REQ-SEC-021, ADR-0034); the assertions exist so a future edit to the record shapes or the
 * redactor cannot silently start (or stop) exposing a field to unauthenticated callers.
 */
class MissionGuestRedactorTest {

  private final MissionGuestRedactor redactor = new MissionGuestRedactor();

  @Test
  void cleanupUserForGuest_stripsPiiButKeepsPublicCallsignTuple() {
    UserDto full = fullUser();

    UserDto redacted = redactor.cleanupUserForGuest(full);

    // Public callsign tuple + non-sensitive scalars kept.
    assertThat(redacted.id()).isEqualTo(full.id());
    assertThat(redacted.username()).isEqualTo("bob.callsign");
    assertThat(redacted.displayName()).isEqualTo("Bob");
    assertThat(redacted.effectiveName()).isEqualTo("Bob");
    assertThat(redacted.rank()).isEqualTo(5);
    assertThat(redacted.inKeycloak()).isTrue();
    assertThat(redacted.version()).isEqualTo(1L);
    // PII + authorization surface dropped.
    assertThat(redacted.email()).isNull();
    assertThat(redacted.description()).isNull();
    assertThat(redacted.roles()).isNull();
    assertThat(redacted.permissions()).isNull();
    assertThat(redacted.lastReadAnnouncementId()).isNull();
    assertThat(redacted.squadron()).isNull();
    assertThat(redacted.squadrons()).isNull();
    assertThat(redacted.joinDate()).isNull();
    assertThat(redacted.discordLinked()).isNull();
    assertThat(redacted.isLogistician()).isFalse();
    assertThat(redacted.isMissionManager()).isFalse();
  }

  @Test
  void cleanupParticipantForGuest_redactsUserButKeepsPayoutCommentAndEditToken() {
    MissionParticipantDto participant =
        participant(fullUser(), PayoutPreference.PAYOUT, "my comment", "edit-token");

    MissionParticipantDto redacted = redactor.cleanupParticipantForGuest(participant);

    assertThat(redacted.user().email()).isNull();
    assertThat(redacted.user().username()).isEqualTo("bob.callsign");
    // Member-peer view keeps payout preference + comment + the M1 self-edit token.
    assertThat(redacted.payoutPreference()).isEqualTo(PayoutPreference.PAYOUT);
    assertThat(redacted.comment()).isEqualTo("my comment");
    assertThat(redacted.guestEditToken()).isEqualTo("edit-token");
  }

  @Test
  void cleanupParticipantForGuest_toleratesNullUser() {
    MissionParticipantDto participant = participant(null, PayoutPreference.DONATE, "c", "tok");

    MissionParticipantDto redacted = redactor.cleanupParticipantForGuest(participant);

    assertThat(redacted.user()).isNull();
    assertThat(redacted.payoutPreference()).isEqualTo(PayoutPreference.DONATE);
  }

  @Test
  void stripOutsiderParticipantFields_nullsPayoutAndCommentButKeepsUserAndToken() {
    UserDto user = fullUser();
    MissionParticipantDto participant =
        participant(user, PayoutPreference.PAYOUT, "my comment", "edit-token");

    MissionParticipantDto stripped = redactor.stripOutsiderParticipantFields(participant);

    assertThat(stripped.comment()).isNull();
    assertThat(stripped.payoutPreference()).isNull();
    // This pass does not touch the user (the peer pass already redacted it) or the edit token.
    assertThat(stripped.user()).isSameAs(user);
    assertThat(stripped.guestEditToken()).isEqualTo("edit-token");
  }

  @Test
  void cleanupMissionForGuest_clearsOwnerManagersFlagsButKeepsDescriptionAndOrg() {
    List<MissionStepDto> steps = List.of();
    SquadronReferenceDto squadron = new SquadronReferenceDto(UUID.randomUUID(), "Kartell", "KRT");
    MissionParticipantDto participant =
        participant(fullUser(), PayoutPreference.PAYOUT, "comment", "tok");
    MissionDto full = mission("secret plan", squadron, participant, steps);

    MissionDto redacted = redactor.cleanupMissionForGuest(full);

    // Owner / managers stripped, edit + manage flags forced off.
    assertThat(redacted.owner()).isNull();
    assertThat(redacted.managers()).isNull();
    assertThat(redacted.canEdit()).isFalse();
    assertThat(redacted.canManageManagers()).isFalse();
    // Member-peer view keeps the free-text description, the organisation and the planning data.
    assertThat(redacted.description()).isEqualTo("secret plan");
    assertThat(redacted.owningSquadron()).isEqualTo(squadron);
    assertThat(redacted.steps()).isSameAs(steps);
    // Participant PII is stripped recursively, but payout + comment survive at the peer level.
    MissionParticipantDto cleaned = redacted.participants().iterator().next();
    assertThat(cleaned.user().email()).isNull();
    assertThat(cleaned.payoutPreference()).isEqualTo(PayoutPreference.PAYOUT);
    assertThat(cleaned.comment()).isEqualTo("comment");
  }

  @Test
  void cleanupOutsiderMissionForGuest_hidesDescriptionAndStripsParticipantPayoutAndComment() {
    List<MissionStepDto> steps = List.of();
    SquadronReferenceDto squadron = new SquadronReferenceDto(UUID.randomUUID(), "Kartell", "KRT");
    MissionParticipantDto participant =
        participant(fullUser(), PayoutPreference.PAYOUT, "comment", "tok");
    MissionDto full = mission("secret plan", squadron, participant, steps);

    MissionDto redacted = redactor.cleanupOutsiderMissionForGuest(full);

    // Strict outsider level: description hidden on top of the peer redaction.
    assertThat(redacted.description()).isNull();
    assertThat(redacted.owner()).isNull();
    // Organisation + planning data still forwarded to outsiders on a non-internal mission.
    assertThat(redacted.owningSquadron()).isEqualTo(squadron);
    assertThat(redacted.steps()).isSameAs(steps);
    // Each participant's payout preference + comment nulled (ADR-0034); user already PII-stripped.
    MissionParticipantDto outsider = redacted.participants().iterator().next();
    assertThat(outsider.user().email()).isNull();
    assertThat(outsider.payoutPreference()).isNull();
    assertThat(outsider.comment()).isNull();
  }

  /**
   * REQ-SEC-040: the nested ship owner must be redacted like any other user leaving the API.
   *
   * <p>{@code assignedUnits} is forwarded to guests as mission planning data, and each unit's
   * {@code ship} carries a full {@link UserDto} owner. Because {@code UserMapper} nulls only the
   * email, an un-redacted pass-through handed an <em>unauthenticated</em> caller of the public
   * mission detail the owner's roles and permissions — i.e. who holds ADMIN/OFFICER — plus their
   * free-text description, org-unit memberships, join date and Discord-link status.
   */
  @Test
  void cleanupMissionForGuest_redactsTheOwnerOfEachAssignedUnitsShip() {
    UserDto shipOwner = fullUser();
    MissionDto full = missionWithUnit(unitWithShipOwnedBy(shipOwner));

    MissionDto redacted = redactor.cleanupMissionForGuest(full);

    UserDto owner = redacted.assignedUnits().getFirst().ship().owner();
    assertThat(owner.roles()).isNull();
    assertThat(owner.permissions()).isNull();
    assertThat(owner.description()).isNull();
    assertThat(owner.email()).isNull();
    assertThat(owner.squadron()).isNull();
    assertThat(owner.squadrons()).isNull();
    assertThat(owner.joinDate()).isNull();
    assertThat(owner.discordLinked()).isNull();
    assertThat(owner.isLogistician()).isFalse();
    assertThat(owner.isMissionManager()).isFalse();
    // The public callsign tuple survives — the unit card still names its ship's owner.
    assertThat(owner.username()).isEqualTo("bob.callsign");
    assertThat(owner.rank()).isEqualTo(5);
    // The unit's own planning fields are untouched.
    assertThat(redacted.assignedUnits().getFirst().name()).isEqualTo("Alpha");
    assertThat(redacted.assignedUnits().getFirst().ship().name()).isEqualTo("Rocinante");
  }

  /** The strict outsider level inherits the peer pass's ship-owner redaction. */
  @Test
  void cleanupOutsiderMissionForGuest_alsoRedactsTheShipOwner() {
    MissionDto full = missionWithUnit(unitWithShipOwnedBy(fullUser()));

    MissionDto redacted = redactor.cleanupOutsiderMissionForGuest(full);

    UserDto owner = redacted.assignedUnits().getFirst().ship().owner();
    assertThat(owner.roles()).isNull();
    assertThat(owner.permissions()).isNull();
    assertThat(owner.description()).isNull();
    assertThat(owner.email()).isNull();
  }

  /** A unit without an assigned ship must not blow up the redaction pass. */
  @Test
  void cleanupUnitForGuest_toleratesAUnitWithoutAShip() {
    MissionUnitDto unit =
        new MissionUnitDto(
            UUID.randomUUID(), "Bravo", null, null, 12.5, false, null, "note", 1L, List.of());

    MissionUnitDto redacted = redactor.cleanupUnitForGuest(unit);

    assertThat(redacted.ship()).isNull();
    assertThat(redacted.name()).isEqualTo("Bravo");
    assertThat(redacted.frequency()).isEqualTo(12.5);
  }

  private static MissionUnitDto unitWithShipOwnedBy(UserDto owner) {
    ShipDto ship =
        new ShipDto(
            UUID.randomUUID(),
            "Rocinante",
            null,
            "LTI",
            null,
            true,
            owner,
            new SquadronReferenceDto(UUID.randomUUID(), "Squadron", "SQ"),
            1L);
    return new MissionUnitDto(
        UUID.randomUUID(), "Alpha", null, ship, 8.5, true, null, "note", 1L, List.of());
  }

  private static MissionDto missionWithUnit(MissionUnitDto unit) {
    SquadronReferenceDto squadron = new SquadronReferenceDto(UUID.randomUUID(), "Kartell", "KRT");
    MissionParticipantDto participant =
        participant(fullUser(), PayoutPreference.PAYOUT, "comment", "tok");
    return mission("secret plan", squadron, participant, List.of(), List.of(unit));
  }

  private static UserDto fullUser() {
    return new UserDto(
        UUID.randomUUID(),
        "bob.callsign",
        "Bob",
        "Bob",
        "bob@example.invalid",
        5,
        "desc",
        Set.of("ROLE_KRT_MEMBER"),
        Set.of("HANGAR_READ"),
        UUID.randomUUID(),
        true,
        true,
        true,
        new SquadronReferenceDto(UUID.randomUUID(), "Squadron", "SQ"),
        List.of(new SquadronReferenceDto(UUID.randomUUID(), "Squadron", "SQ")),
        1L,
        LocalDate.EPOCH,
        true);
  }

  private static MissionParticipantDto participant(
      UserDto user, PayoutPreference payout, String comment, String token) {
    return new MissionParticipantDto(
        UUID.randomUUID(), user, null, null, null, null, comment, null, null, payout, 1L, token);
  }

  private static MissionDto mission(
      String description,
      SquadronReferenceDto squadron,
      MissionParticipantDto participant,
      List<MissionStepDto> steps) {
    return mission(description, squadron, participant, steps, List.of());
  }

  private static MissionDto mission(
      String description,
      SquadronReferenceDto squadron,
      MissionParticipantDto participant,
      List<MissionStepDto> steps,
      List<MissionUnitDto> assignedUnits) {
    UserReferenceDto owner = new UserReferenceDto(UUID.randomUUID(), "owner", "Owner", "Owner", 9);
    return new MissionDto(
        UUID.randomUUID(),
        "Operation X",
        description,
        "https://cal.example.invalid",
        "PLANNED",
        null,
        null,
        null,
        null,
        null,
        false,
        Set.of(participant),
        assignedUnits,
        List.of(),
        null,
        owner,
        Set.of(new UserReferenceDto(UUID.randomUUID(), "mgr", "Mgr", "Mgr", 8)),
        true,
        true,
        1L,
        2L,
        3L,
        4L,
        3,
        4,
        squadron,
        5L,
        new UserReferenceDto(UUID.randomUUID(), "lead", "Lead", "Lead", 7),
        "Guest Lead",
        6L,
        steps,
        7L,
        List.of(),
        8L,
        "Meeting Point A");
  }
}
