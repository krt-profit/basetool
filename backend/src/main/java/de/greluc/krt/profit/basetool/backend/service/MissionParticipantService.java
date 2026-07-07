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

package de.greluc.krt.profit.basetool.backend.service;

import static de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.assertSectionVersion;
import static de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.bumpSectionVersion;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.MissionSection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the mission participant lifecycle: sign-up (registered user and anonymous guest), the
 * per-participant attribute edit, check-in / check-out, payout-preference changes, removal, the
 * party-lead assignment, and co-manager add/remove. Extracted from {@code MissionService} (L1 step
 * 2, #920) so the participants responsibility no longer shares that god-class's dependencies.
 *
 * <p>Concurrency (REQ-ORG-018 / the CLAUDE.md optimistic-lock rules) is preserved verbatim across
 * the move: participant add/remove/attribute edits deliberately do <strong>not</strong> {@code
 * save(mission)} (the participants collection is {@code @OptimisticLock(excluded = true)}), so they
 * never bump the row's {@code @Version} and never 409 a concurrent mission-level edit; the
 * per-participant edit guards on the participant's own {@code @Version}; and the party-lead change
 * guards + bumps only the {@code partyLeadVersion} section counter through {@code
 * MissionSectionVersions}. {@code MissionService} keeps its public participant methods as thin
 * delegations, so the controller and transaction boundaries are unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissionParticipantService {

  /** Repository for the mission aggregate root (fetch-or-throw). */
  private final MissionRepository missionRepository;

  /** Repository for participant rows (explicit save/flush to avoid bumping {@code Mission}). */
  private final MissionParticipantRepository missionParticipantRepository;

  /** Repository used to resolve a linked user and the guest-name promotion match. */
  private final UserRepository userRepository;

  /** Repository used to resolve the desired/planned mission job type references. */
  private final JobTypeRepository jobTypeRepository;

  /** Mints and hashes the per-row guest-edit capability token (REQ-SEC-018). */
  private final GuestParticipantTokenService guestParticipantTokenService;

  /** Resolves a registered user's org-unit memberships to stamp on the participant row. */
  private final OrgUnitMembershipService orgUnitMembershipService;

  /** Polymorphic repository used to materialise org-unit affiliations by id. */
  private final OrgUnitRepository orgUnitRepository;

  /** Records the state-mutating participant activities into the audit log (REQ-AUDIT-001). */
  private final AuditService auditService;

  /**
   * Adds an authenticated user as a participant on a mission. Convenience overload that delegates
   * to the full-form {@link #addParticipant(UUID, ParticipantForm)} with default values for the
   * optional fields.
   */
  @Transactional
  public Mission addParticipant(@NotNull UUID missionId, @NotNull UUID userId) {
    return addParticipant(missionId, userId, null, null, null, null, null);
  }

  /**
   * Mid-form participant add — accepts a user reference, optional guest name (when the user isn't
   * authenticated), an optional desired job type, and an optional comment. Convenience overload
   * that delegates to the full form with {@code orgUnitIds=null} and no explicit payout choice.
   */
  @Transactional
  public Mission addParticipant(
      @NotNull UUID missionId,
      UUID userId,
      String guestName,
      UUID desiredJobTypeId,
      String comment) {
    return addParticipant(missionId, userId, guestName, desiredJobTypeId, comment, null, null);
  }

  /**
   * Full-form participant add. Resolves the user reference from {@code userId} (or by
   * case-insensitive {@code guestName} match against existing users — promotes a guest entry to a
   * linked-user entry when the guest name turns out to be a real member).
   *
   * <p>Org-unit affiliations are stamped per kind of participant:
   *
   * <ul>
   *   <li><b>Registered user</b> — affiliations are auto-derived from the user's memberships (every
   *       Staffel and Spezialkommando they belong to); the submitted {@code orgUnitIds} are
   *       ignored. A user with no membership at all gets no affiliation (no more wrong IRIDIUM
   *       fallback).
   *   <li><b>Guest</b> — the caller-submitted {@code orgUnitIds} are honoured after the
   *       authorization filter in {@link #resolveGuestSubmittedOrgUnits(java.util.List)} (anonymous
   *       callers cannot label a guest at all; authenticated callers may label only org units they
   *       can edit).
   * </ul>
   *
   * <p>{@code payoutPreference} (nullable) fixes the per-mission payout choice at sign-up time —
   * the sign-up modal's "Auszahlungsart" select. A non-null value wins over the registered user's
   * profile default (REQ-MISSION-002); {@code null} keeps the existing default chain (profile
   * default for users, entity default {@code PAYOUT} for guests).
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when any referenced
   *     id is unknown
   */
  @Transactional
  public Mission addParticipant(
      @NotNull UUID missionId,
      UUID userId,
      String guestName,
      UUID desiredJobTypeId,
      String comment,
      java.util.List<UUID> orgUnitIds,
      PayoutPreference payoutPreference) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    // Audit finding M-4: hard cap of {@value MissionService#MAX_PARTICIPANTS_PER_MISSION} per
    // mission. Closes the DoS vector where an anonymous caller scripts thousands of guest sign-ups
    // until the mission_participant table holds millions of rows for a single mission and {@code
    // mission.getParticipants()} (eager-fetched via the findById EntityGraph) starts scanning
    // hundreds of MB per request. 500 covers every realistic IRIDIUM-scale operation by a large
    // margin; override via a Squadron-level property is a follow-up if ever needed.
    if (mission.getParticipants() != null
        && mission.getParticipants().size() >= MissionService.MAX_PARTICIPANTS_PER_MISSION) {
      throw new de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException(
          "Mission participant cap reached ("
              + MissionService.MAX_PARTICIPANTS_PER_MISSION
              + "). Remove inactive participants before adding more.");
    }

    UUID effectiveUserId = userId;
    String effectiveGuestName = guestName;

    if (effectiveUserId == null && effectiveGuestName != null && !effectiveGuestName.isBlank()) {
      Optional<User> matchedUser =
          userRepository.findByUsernameIgnoreCaseOrDisplayNameIgnoreCase(
              effectiveGuestName.trim(), effectiveGuestName.trim());
      if (matchedUser.isPresent()) {
        effectiveUserId = matchedUser.orElseThrow().getId();
        effectiveGuestName = null;
      }
    }

    if (effectiveUserId == null && (effectiveGuestName == null || effectiveGuestName.isBlank())) {
      throw new IllegalArgumentException("Either User ID or Guest Name must be provided.");
    }

    final UUID finalUserId = effectiveUserId;
    final String finalGuestName = effectiveGuestName;

    // Check for duplicates
    if (finalUserId != null) {
      boolean exists =
          mission.getParticipants().stream()
              .anyMatch(p -> p.getUser() != null && p.getUser().getId().equals(finalUserId));
      if (exists) {
        throw new de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException(
            "error.mission.participant.duplicate.user");
      }
    } else if (finalGuestName != null && !finalGuestName.isBlank()) {
      boolean exists =
          mission.getParticipants().stream()
              .anyMatch(p -> finalGuestName.equalsIgnoreCase(p.getGuestName()));
      if (exists) {
        throw new de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException(
            "error.mission.participant.duplicate.guest");
      }
    }

    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);

    if (effectiveUserId != null) {
      User user =
          userRepository
              .findById(effectiveUserId)
              .orElseThrow(() -> new NotFoundException("User not found"));
      participant.setUser(user);
      // Registered users carry every org unit they belong to at participate-time (their Staffel
      // and/or any Spezialkommandos). Auto-derived from org_unit_membership — empty when the user
      // belongs to none (admins / brand-new accounts) so the roster shows no affiliation instead of
      // the old, wrong IRIDIUM fallback. The caller-submitted orgUnitIds are intentionally ignored
      // for registered participants; the picker is guest-only.
      participant.setOrgUnits(resolveMembershipOrgUnits(user.getId()));
      // REQ-MISSION-002: pre-fill the per-participant payout preference from the signing-up user's
      // personal default. A user who never chose one keeps the entity default (PAYOUT). This is a
      // one-time seed at sign-up — the per-mission value stays editable afterwards via
      // updateParticipantAttributes and is NOT rewritten when the user later changes their profile
      // default. Guests (the else branch) have no profile and keep PAYOUT.
      if (user.getDefaultPayoutPreference() != null) {
        participant.setPayoutPreference(user.getDefaultPayoutPreference());
      }
    } else {
      participant.setGuestName(effectiveGuestName);
      participant.setOrgUnits(resolveGuestSubmittedOrgUnits(orgUnitIds));
      // Security audit M1 / REQ-SEC-018: bind this anonymous guest sign-up to its creator with a
      // per-row capability token. Only the hash is persisted; the plaintext rides back on the
      // transient field so the create response can hand it to the caller exactly once. A later
      // guest
      // mutate/delete must present the token (or hold a mission-management role) — enforced by
      // MissionSecurityService.canAccessParticipant.
      String mintedGuestEditToken = guestParticipantTokenService.generateToken();
      participant.setGuestEditTokenHash(
          guestParticipantTokenService.hashToken(mintedGuestEditToken));
      participant.setGuestEditToken(mintedGuestEditToken);
    }

    if (desiredJobTypeId != null) {
      JobType job = jobTypeRepository.findById(desiredJobTypeId).orElse(null);
      participant.setDesiredMissionJobType(job);
    }

    // An explicit sign-up choice (the modal's Auszahlungsart select) wins over the profile-default
    // seeding above; null keeps the default chain untouched.
    if (payoutPreference != null) {
      participant.setPayoutPreference(payoutPreference);
    }

    participant.setComment(comment);

    mission.getParticipants().add(participant);
    missionParticipantRepository.save(participant);
    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_ADDED,
        mission.getId(),
        mission.getName(),
        finalUserId,
        AuditDetails.of("participant", participant.getId())
            .with("type", finalUserId != null ? "user" : "guest"));
    // NOTE: no explicit missionRepository.save(mission) here.
    // The collection is @OptimisticLock(excluded = true) so Hibernate's dirty-check
    // on commit persists the new participant (via cascade) without bumping the parent
    // Mission.version. This is key for the multi-user concurrency design (Option A):
    // adding a participant must NOT invalidate other users' open forms on the same mission.
    //
    // No `flush()` here on purpose. The in-memory `anyMatch` check above is the
    // primary duplicate detector (returns a localized DuplicateEntityException → 409).
    // The Stufe-2 DB-level backstop is the partial unique index `uq_mission_participant_user`
    // (Flyway V96): a TOCTOU-raced double-signup (double-click, two tabs) slips past the
    // in-memory check, both inserts head for the same (mission, user) key, and PostgreSQL
    // rejects the second one at commit time as a unique-constraint violation. Spring
    // wraps that as DataIntegrityViolationException and the GlobalExceptionHandler maps
    // it to 409 — the same HTTP status the in-memory branch produces, so the frontend
    // toast logic (`MissionPageController#addParticipant`, status-code-based) shows the
    // user the same error. A service-side translation to DuplicateEntityException was
    // attempted but required `saveAndFlush`, which forces a session-wide flush and
    // breaks @Transactional tests that intentionally hold half-built sibling entities
    // in the persistence context until rollback.
    return mission;
  }

  /**
   * Look up a single participant on a mission. Verifies the participant actually belongs to the
   * named mission — a participant id that exists but is attached to a different mission throws 404,
   * matching what {@link MissionSecurityService#canAccessParticipant} expects.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the participant
   *     does not exist on this mission
   */
  public MissionParticipant getParticipant(@NotNull UUID missionId, @NotNull UUID participantId) {
    return missionParticipantRepository
        .findById(participantId)
        .filter(p -> p.getMission().getId().equals(missionId))
        .orElseThrow(() -> new NotFoundException("Participant not found in mission"));
  }

  /**
   * Returns all participants of a mission that are not yet assigned to any unit (crew). Used to
   * filter the "Crew zuweisen" dropdown so only unassigned participants are selectable.
   *
   * @param missionId mission id
   * @return list of unassigned participants
   */
  public List<MissionParticipant> getUnassignedParticipants(@NotNull UUID missionId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    Set<UUID> assignedParticipantIds =
        mission.getAssignedUnits().stream()
            .flatMap(unit -> unit.getCrew().stream())
            .map(crew -> crew.getParticipant().getId())
            .collect(java.util.stream.Collectors.toSet());
    return mission.getParticipants().stream()
        .filter(p -> !assignedParticipantIds.contains(p.getId()))
        .toList();
  }

  /**
   * Removes a participant from a mission and the participant's linked finance entries. The
   * participant row itself is deleted (not soft-deleted); finance entries with FK to the
   * participant cascade-delete to keep the FK constraint happy.
   */
  @Transactional
  public Mission removeParticipant(@NotNull UUID missionId, @NotNull UUID participantId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    boolean removed = mission.getParticipants().removeIf(p -> p.getId().equals(participantId));

    if (!removed) {
      throw new NotFoundException("Participant not found in this mission");
    }

    // Also remove from any crews in this mission
    for (MissionUnit ship : mission.getAssignedUnits()) {
      ship.getCrew()
          .removeIf(
              crew ->
                  crew.getParticipant() != null
                      && crew.getParticipant().getId().equals(participantId));
    }

    // NOTE: no explicit missionRepository.save(mission). orphanRemoval + @OptimisticLock(excluded)
    // on participants/assignedUnits ensures dirty-flush on commit without bumping Mission.version.
    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_REMOVED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("participant", participantId));
    return mission;
  }

  /**
   * Updates a participant's per-mission attributes (job type, ship, unit, crew, guest name, payout
   * preference). Per-participant optimistic lock via the participant's own {@code @Version} field —
   * the wider mission's version is NOT bumped, so concurrent participant edits don't collide with
   * each other or with mission-level edits.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the participant
   *     or any referenced id is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when stale
   */
  @Transactional
  public Mission updateParticipantAttributes(
      @NotNull UUID missionId,
      @NotNull UUID participantId,
      UUID desiredMissionJobTypeId,
      UUID plannedMissionJobTypeId,
      String comment,
      Instant startTime,
      Instant endTime,
      java.util.List<UUID> orgUnitIds,
      PayoutPreference payoutPreference,
      String guestName,
      Long version) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    MissionParticipant participant =
        mission.getParticipants().stream()
            .filter(p -> p.getId().equals(participantId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Participant not found in this mission"));

    if (version != null && !version.equals(participant.getVersion())) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          MissionParticipant.class, participant.getId());
    }

    if (payoutPreference != null) {
      participant.setPayoutPreference(payoutPreference);
      missionParticipantRepository.save(participant);
      missionParticipantRepository.flush();
    }

    if (desiredMissionJobTypeId != null) {
      JobType jt =
          jobTypeRepository
              .findById(desiredMissionJobTypeId)
              .orElseThrow(() -> new NotFoundException("Desired JobType not found"));
      if (jt.getArchetype() != JobTypeArchetype.MISSION) {
        throw new IllegalArgumentException(
            "Desired JobType " + jt.getName() + " is not of archetype MISSION");
      }
      participant.setDesiredMissionJobType(jt);
    } else {
      participant.setDesiredMissionJobType(null);
    }

    if (participant.getUser() != null) {
      // Registered users carry every org unit they belong to at participate-time. Re-derives from
      // org_unit_membership on every update so a freshly-assigned Staffel / SK propagates into the
      // participant row. The submitted orgUnitIds are ignored for registered participants — the
      // picker is guest-only, and the affiliation is the user's actual membership set.
      participant.setOrgUnits(resolveMembershipOrgUnits(participant.getUser().getId()));
    } else {
      // Audit finding M-3 (2026-05-20): logging the raw {@code guestName} leaks PII —
      // free-text names often contain real-life names of third parties that PiiMasker does not
      // catch (regex covers emails / JWTs / token keywords only). Log just the participant id;
      // the linked-vs-guest distinction is implicit because the linked-user branch above logged
      // nothing either.
      log.info("Updating guest participant: {}", participant.getId());
      if (guestName != null) {
        participant.setGuestName(guestName);
      }
      participant.setOrgUnits(resolveGuestSubmittedOrgUnits(orgUnitIds));
    }

    if (plannedMissionJobTypeId != null) {
      JobType jt =
          jobTypeRepository
              .findById(plannedMissionJobTypeId)
              .orElseThrow(() -> new NotFoundException("Planned JobType not found"));
      if (jt.getArchetype() != JobTypeArchetype.MISSION) {
        throw new IllegalArgumentException(
            "Planned JobType " + jt.getName() + " is not of archetype MISSION");
      }
      // A mission may have only one "Einsatzleiter" (the participant whose planned job type is the
      // designated mission-lead type, JobType.isMissionLead). Reject assigning it to a second
      // participant (REQ-MISSION-013) — the editor must first clear the existing one.
      if (jt.isMissionLead()) {
        boolean alreadyTaken =
            mission.getParticipants().stream()
                .anyMatch(
                    other ->
                        !other.getId().equals(participant.getId())
                            && other.getPlannedMissionJobType() != null
                            && other.getPlannedMissionJobType().isMissionLead());
        if (alreadyTaken) {
          throw new de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException(
              "A mission can have only one Einsatzleiter (mission lead).");
        }
      }
      participant.setPlannedMissionJobType(jt);
    } else {
      participant.setPlannedMissionJobType(null);
    }

    participant.setComment(comment);

    if (startTime != null) {
      if (mission.getActualStartTime() == null) {
        throw new IllegalArgumentException(
            "Cannot set participant start time before mission actual start time is set");
      }
    }

    if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
      throw new IllegalArgumentException("Start time cannot be after end time");
    }

    participant.setStartTime(startTime);
    participant.setEndTime(endTime);

    // Persist the participant explicitly; avoid save(mission) to keep Mission.version stable.
    missionParticipantRepository.save(participant);
    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_UPDATED,
        mission.getId(),
        mission.getName(),
        participant.getUser() != null ? participant.getUser().getId() : null,
        AuditDetails.of("participant", participantId));
    return mission;
  }

  /**
   * Marks a participant as checked in. Sets {@code startTime} to {@code now()} only if the
   * participant has not been checked in before — a repeated check-in is a no-op so the original
   * arrival time is preserved.
   *
   * <p>The idempotency guard is a data-integrity requirement, not a nicety: {@code startTime} feeds
   * the credited-time payout breakdown, so a second check-in that reset it to a later {@code now()}
   * would silently shrink the participant's credited duration and skew the money distribution. The
   * endpoint carries no client version and any stale crew-board view still renders the "Einchecken"
   * button (REQ-FE-010 staleness window), so a duplicate delivery is realistic — the guard makes it
   * harmless instead of a silent lost update (#1134). {@code checkOut} keeps the opposite,
   * override-on-repeat semantics on purpose (late check-out corrections).
   */
  @Transactional
  public Mission checkIn(UUID missionId, UUID participantId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    MissionParticipant participant = getParticipant(missionId, participantId);
    if (mission.getActualStartTime() == null) {
      throw new IllegalArgumentException("Cannot check in before mission actual start time is set");
    }
    if (participant.getStartTime() != null) {
      // Already checked in: preserve the original arrival timestamp and record nothing — a repeated
      // check-in changed no state, so it must not overwrite startTime nor emit a second
      // CHECKED_IN audit event that would imply an arrival that did not happen.
      return mission;
    }
    participant.setStartTime(Instant.now());
    missionParticipantRepository.save(participant);
    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_CHECKED_IN,
        mission.getId(),
        mission.getName(),
        participant.getUser() != null ? participant.getUser().getId() : null,
        AuditDetails.of("participant", participantId));
    return mission;
  }

  /**
   * Marks a participant as checked out. Sets {@code endTime} to {@code now()}. Unlike check-in, a
   * repeated check-out overrides the previous timestamp — late check-out corrections.
   */
  @Transactional
  public Mission checkOut(UUID missionId, UUID participantId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    MissionParticipant participant = getParticipant(missionId, participantId);
    if (mission.getActualEndTime() != null && Instant.now().isAfter(mission.getActualEndTime())) {
      if (participant.getStartTime() != null
          && mission.getActualEndTime().isBefore(participant.getStartTime())) {
        participant.setEndTime(participant.getStartTime());
      } else {
        participant.setEndTime(mission.getActualEndTime());
      }
    } else {
      participant.setEndTime(Instant.now());
    }
    missionParticipantRepository.save(participant);
    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_CHECKED_OUT,
        mission.getId(),
        mission.getName(),
        participant.getUser() != null ? participant.getUser().getId() : null,
        AuditDetails.of("participant", participantId));
    return mission;
  }

  /**
   * Updates a participant's payout preference (PAYOUT or DONATE). Guests can change their own
   * preference even without authentication — the security gate on the participant row keeps other
   * users' preferences locked.
   */
  @Transactional
  public Mission updatePayoutPreference(
      UUID missionId, UUID participantId, PayoutPreference preference) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    MissionParticipant participant =
        mission.getParticipants().stream()
            .filter(p -> p.getId().equals(participantId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Participant not found in mission"));

    if (preference != null) {
      participant.setPayoutPreference(preference);
      missionParticipantRepository.save(participant);
      missionParticipantRepository.flush();
      auditService.record(
          AuditEventType.MISSION_PARTICIPANT_UPDATED,
          mission.getId(),
          mission.getName(),
          participant.getUser() != null ? participant.getUser().getId() : null,
          AuditDetails.of("participant", participantId).with("field", "payoutPreference"));
    }
    return mission;
  }

  /**
   * Sets (or clears) the mission's party lead — either a registered user, a free-text guest name,
   * or nothing. Guarded and bumped through the dedicated {@code partyLeadVersion} section counter
   * so changing the lead never collides with a concurrent core / schedule / flags edit
   * (REQ-ORG-018).
   *
   * @param missionId the mission id
   * @param userId the registered user to set as party lead, or {@code null}
   * @param guestName the free-text party-lead name when {@code userId} is {@code null}, or blank to
   *     clear
   * @param expectedPartyLeadVersion expected value of {@code Mission.partyLeadVersion}
   * @return the managed mission with the party lead applied
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission or
   *     the referenced user is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedPartyLeadVersion} is stale
   */
  @Transactional
  public Mission setPartyLead(
      @NotNull UUID missionId,
      UUID userId,
      String guestName,
      @NotNull Long expectedPartyLeadVersion) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    assertSectionVersion(mission, MissionSection.PARTY_LEAD, expectedPartyLeadVersion, missionId);

    if (userId != null) {
      User user =
          userRepository
              .findById(userId)
              .orElseThrow(() -> new NotFoundException("User not found"));
      mission.setPartyLeadUser(user);
      mission.setPartyLeadGuestName(null);
    } else if (guestName != null && !guestName.isBlank()) {
      mission.setPartyLeadUser(null);
      mission.setPartyLeadGuestName(guestName.trim());
    } else {
      mission.setPartyLeadUser(null);
      mission.setPartyLeadGuestName(null);
    }

    bumpSectionVersion(mission, MissionSection.PARTY_LEAD);
    Mission saved = missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_PARTY_LEAD_CHANGED,
        mission.getId(),
        mission.getName(),
        userId,
        AuditDetails.of(
            "kind",
            userId != null
                ? "user"
                : (guestName != null && !guestName.isBlank() ? "guest" : "cleared")));
    return saved;
  }

  /**
   * Adds a co-manager. Co-managers can edit the mission with the same privileges as the owner
   * (except they cannot transfer ownership — see {@link MissionSecurityService#canChangeOwner}).
   */
  @Transactional
  public Mission addManager(@NotNull UUID missionId, @NotNull UUID userId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    mission.getManagers().add(user);
    auditService.record(
        AuditEventType.MISSION_MANAGER_ADDED, mission.getId(), mission.getName(), userId, null);
    return mission;
  }

  /**
   * Removes a co-manager. The owner cannot be removed via this method — use {@link
   * MissionService#setMissionOwner} to transfer ownership first.
   */
  @Transactional
  public Mission removeManager(@NotNull UUID missionId, @NotNull UUID userId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    mission.getManagers().removeIf(u -> u.getId().equals(userId));
    auditService.record(
        AuditEventType.MISSION_MANAGER_REMOVED, mission.getId(), mission.getName(), userId, null);
    return mission;
  }

  /**
   * Resolves every org unit a registered user belongs to (Staffel and/or Spezialkommandos) into the
   * managed {@link OrgUnit} entities to stamp on the participant. Reads the membership rows via
   * {@link OrgUnitMembershipService#findAllMembershipsForUser(UUID)} (already Staffel-first, then
   * SK alphabetical) and materialises each org-unit id through the polymorphic {@code
   * OrgUnitRepository}. A user with no memberships yields an empty list — there is deliberately no
   * IRIDIUM fallback, so an admin who belongs to nothing shows no affiliation on the roster.
   *
   * @param userId the registered user whose memberships to resolve; never {@code null}.
   * @return the managed org-unit entities, membership order preserved; never {@code null}, possibly
   *     empty.
   */
  private java.util.List<OrgUnit> resolveMembershipOrgUnits(@NotNull UUID userId) {
    java.util.List<UUID> orgUnitIds =
        orgUnitMembershipService.findAllMembershipsForUser(userId).stream()
            .map(m -> m.getId().getOrgUnitId())
            .toList();
    if (orgUnitIds.isEmpty()) {
      return java.util.List.of();
    }
    // One batched lookup instead of findById per membership; re-key to preserve membership order
    // and drop any id that no longer resolves (mirrors the previous nonNull filter).
    java.util.Map<UUID, OrgUnit> byId =
        orgUnitRepository.findAllById(orgUnitIds).stream()
            .collect(java.util.stream.Collectors.toMap(OrgUnit::getId, o -> o));
    return orgUnitIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
  }

  /**
   * Resolves a caller-submitted {@code orgUnitIds} list for a GUEST participant entry to the org
   * units to persist. A guest's org-unit affiliation is mission-scoped roster metadata only: it is
   * a label on a single mission's participant row, drives nothing but the roster badges (see {@code
   * MissionMapper.orgUnitsToReferenceDtos}), grants no permissions and touches no user data. Anyone
   * who may add the guest at all — the endpoint's {@code canSeeMission} gate already governs that,
   * including anonymous sign-ups on public (non-internal) missions — may therefore label it with
   * any Staffel or SK:
   *
   * <ul>
   *   <li>{@code null} / empty input → empty list (no affiliation).
   *   <li>otherwise → every id that resolves to a real {@link OrgUnit} is kept, in submission
   *       order; a {@code null} or unknown id is silently skipped (a roster mislabel is not a
   *       forgery worth a 403, and a non-resolving id simply yields no badge).
   * </ul>
   *
   * <p>This deliberately drops the former audit-H-3 authorization filter (admin / own-membership
   * required) for guests: that gate denied an SK lead — and every anonymous sign-up — from tagging
   * a guest with the relevant org unit, even though the tag carries no authority. Registered-user
   * participants are unaffected: their affiliations are auto-derived from their actual memberships
   * in the caller paths, never from this submitted list.
   *
   * @param submittedOrgUnitIds the caller-supplied org-unit ids from the request DTO.
   * @return the managed org-unit entities to persist on the guest participant; never {@code null},
   *     possibly empty.
   */
  private java.util.List<OrgUnit> resolveGuestSubmittedOrgUnits(
      java.util.List<UUID> submittedOrgUnitIds) {
    if (submittedOrgUnitIds == null || submittedOrgUnitIds.isEmpty()) {
      return java.util.List.of();
    }
    java.util.List<OrgUnit> resolved = new java.util.ArrayList<>();
    for (UUID orgUnitId : submittedOrgUnitIds) {
      if (orgUnitId == null) {
        continue;
      }
      orgUnitRepository.findById(orgUnitId).ifPresent(resolved::add);
    }
    return resolved;
  }
}
