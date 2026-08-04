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

import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the local {@code app_user} mirror of Keycloak users: the identity seam, the squadron-
 * scoped user reads that back the pickers/lists, the self-service profile edits (rank, description,
 * displayName, joinDate, payout preference, blueprint-sharing, read-announcement state) and the
 * single-POST membership-delta orchestrator.
 *
 * <p>The service is the architectural seam where the project's "every read filters by JWT sub" rule
 * (CLAUDE.md) is enforced — {@link #getUserIdFromJwt(Jwt)} / {@link #getCurrentUser()} are the
 * canonical source for the calling user's id and most other services delegate here rather than
 * reaching for {@code SecurityContextHolder} (which is forbidden outside this seam by the ArchUnit
 * rule). JWT subject parsing is fail-closed: a missing {@code sub} or a non-UUID subject is
 * rejected rather than falling back to a derived identifier — silently mapping different Keycloak
 * realms onto the same local id is a worse failure mode than refusing the request.
 *
 * <p>The Keycloak reconciliation (per-login JWT sync + scheduled Admin-API sync + soft-delete
 * reconcile) lives in {@link UserReconciliationService}, and the registration approval lifecycle in
 * {@link UserRegistrationService}; both consult this seam for the JWT-subject resolution.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserService {

  private final UserRepository userRepository;
  private final AuthHelperService authHelperService;
  private final OwnerScopeService ownerScopeService;
  private final OrgUnitMembershipService orgUnitMembershipService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  /**
   * Convenience predicate: does any user have this exact name (case-insensitive) as either username
   * or displayName? Used by participant-add flows to detect "this guest name is actually a known
   * member".
   *
   * @param name candidate name
   * @return true when at least one match exists
   */
  public boolean isUsernameOrDisplayNameTaken(@NotNull String name) {
    return !findMatchesByExactName(name).isEmpty();
  }

  /**
   * Resolves a free-text participant name to existing users by case-insensitive exact match on
   * {@code username} or {@code displayName}. The input is trimmed. An empty or blank name yields an
   * empty result without hitting the database.
   *
   * <p>Used by participant-add flows to translate free-text input (when the user did not pick an
   * entry from the autocomplete dropdown) into a concrete user reference, so that a member is
   * correctly linked instead of being (wrongly) rejected as a duplicate guest name.
   */
  @NotNull
  public List<User> findMatchesByExactName(@NotNull String name) {
    String trimmed = name.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    return userRepository.findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase(trimmed, trimmed);
  }

  /**
   * Extracts the user id from the JWT's {@code sub} claim.
   *
   * <p>Fail-closed validation: a missing {@code sub} or a non-UUID value throws {@link
   * org.springframework.security.authentication.AuthenticationServiceException} rather than falling
   * back to a derived id. Silently mapping different Keycloak realms (or two realms with similar
   * usernames) onto the same local id is a worse failure mode than refusing the request — see the
   * explicit AGENTS.md / CLAUDE.md guidance on stable identity.
   *
   * @param jwt validated JWT
   * @return the {@code sub} parsed as UUID
   * @throws org.springframework.security.authentication.AuthenticationServiceException when sub is
   *     missing or not a UUID
   */
  @NotNull
  public UUID getUserIdFromJwt(@NotNull Jwt jwt) {
    String sub = jwt.getSubject();
    if (sub == null) {
      // The OIDC standard requires `sub` on every ID token. A missing subject
      // indicates a misconfigured authorization server. Refuse rather than
      // falling back to a different claim and silently identifying users by
      // a value an admin might rename in Keycloak.
      // Audit finding H-10: only log the claim keys, never the values. The claims map still
      // carries PII (preferred_username / email — and, on a Keycloak that has not yet had its
      // name mappers removed, possibly given_name / family_name) which PiiMasker only partially
      // scrubs — the keys still help diagnose a Keycloak mapper misconfiguration.
      log.error(
          "JWT has no subject (sub). Refusing the request. Claim keys: {}",
          jwt.getClaims().keySet());
      throw new org.springframework.security.authentication.AuthenticationServiceException(
          "JWT subject (sub) must be present");
    }

    try {
      return UUID.fromString(sub);
    } catch (IllegalArgumentException e) {
      // Standard Keycloak issues UUIDs as subjects. A non-UUID sub is a
      // configuration deviation; deriving a UUID via UUID.nameUUIDFromBytes
      // would mix up identities (renaming the underlying value, two realms
      // with similar usernames, casing differences, ...). Fail-closed.
      log.error(
          "JWT subject is not a valid UUID: '{}'. Refusing the request to avoid identity mix-up.",
          sub);
      throw new org.springframework.security.authentication.AuthenticationServiceException(
          "JWT subject must be a UUID");
    }
  }

  /**
   * Updates a user's editable attributes (rank, description, displayName, joinDate). Optimistic-
   * lock check is explicit when {@code version} is non-null; admins can override by passing {@code
   * null}.
   *
   * <p>Rank validation enforces the role-based range: officers get 1–12, squadron members get
   * 13–20. Out-of-range rank throws {@link IllegalArgumentException} → 400. {@code joinDate} can be
   * explicitly set to {@code null} to clear the field; the other nullable fields are only updated
   * when supplied.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   * @throws IllegalArgumentException when the rank is outside the role-permitted range
   */
  @Transactional
  @NotNull
  public User updateUserAttributes(
      @NotNull UUID id,
      @Nullable Integer rank,
      @Nullable String description,
      @Nullable String displayName,
      @Nullable Long version,
      @Nullable java.time.LocalDate joinDate) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));

    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);

    if (rank != null) {
      boolean isOfficer =
          user.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase(Roles.OFFICER));
      boolean isSquadronMember =
          user.getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase(Roles.KRT_MEMBER));

      if (isOfficer) {
        if (rank < 1 || rank > 12) {
          throw new IllegalArgumentException("Officers can only have rank 1-12");
        }
      } else if (isSquadronMember) {
        if (rank < 13 || rank > 20) {
          throw new IllegalArgumentException("Squadron members can only have rank 13-20");
        }
      }
      user.setRank(rank);
    }
    if (description != null) {
      user.setDescription(description);
    }
    if (displayName != null) {
      user.setDisplayName(displayName.isBlank() ? null : displayName);
    }
    // joinDate can be explicitly set to null (clear the date)
    user.setJoinDate(joinDate);
    return userRepository.save(user);
  }

  /**
   * Narrower update than {@link #updateUserAttributes}: covers only the profile-page editable
   * subset (description + displayName). Used by the user's own profile-edit form so a regular user
   * cannot bump their rank.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public User updateUserDescription(
      @NotNull UUID id,
      @Nullable String description,
      @Nullable String displayName,
      @Nullable Long version) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    if (description != null) {
      user.setDescription(description);
    }
    if (displayName != null) {
      user.setDisplayName(displayName.isBlank() ? null : displayName);
    }
    // saveAndFlush so the bumped @Version is in the response — the profile page writes the returned
    // version back onto every hidden version input in place via syncAllVersions (no reload), so a
    // stale save() version 409s the next consecutive profile edit.
    return userRepository.saveAndFlush(user);
  }

  /**
   * Sets the calling user's personal default payout preference — the value pre-filled into the
   * per-participant {@code payoutPreference} at mission sign-up ({@link
   * MissionService#addParticipant}). Mirrors {@link #updateUserDescription}'s optimistic-lock
   * contract: a stale {@code version} surfaces as a 409. Unlike the description fields the
   * preference is set unconditionally (the request DTO enforces {@code @NotNull}), so this never
   * silently no-ops. Changing it is forward-only — it does not rewrite existing {@code
   * MissionParticipant} rows.
   *
   * @param id the calling user's id, resolved from the JWT (never from the URL); never {@code
   *     null}.
   * @param preference the new default payout preference; never {@code null}.
   * @param version the optimistic-lock version the caller last read; {@code null} bypasses the
   *     check, matching {@link #updateUserDescription}.
   * @return the persisted user with the updated default and bumped version.
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown.
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale.
   */
  @Transactional
  public User updateUserDefaultPayoutPreference(
      @NotNull UUID id, @NotNull PayoutPreference preference, @Nullable Long version) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    user.setDefaultPayoutPreference(preference);
    // saveAndFlush so the bumped @Version reaches the response — the profile payout-preference
    // dropdown writes the returned version back in place via syncAllVersions (no reload), so a
    // stale save version 409s the next consecutive change.
    return userRepository.saveAndFlush(user);
  }

  /**
   * Sets the calling user's opt-in flag for global blueprint sharing. When {@code true}, the user's
   * owned blueprints are counted in the leadership blueprint-availability overview and the
   * item-order blueprint-coverage view for <em>every</em> org unit, not only the ones they belong
   * to (REQ-INV-018). Mirrors {@link #updateUserDefaultPayoutPreference}'s optimistic-lock
   * contract: a stale {@code version} surfaces as a 409. The flag is set unconditionally; the
   * widening is read-only and the viewer-access gates are unchanged.
   *
   * @param id the calling user's id, resolved from the JWT (never from the URL); never {@code
   *     null}.
   * @param shareBlueprintsGlobally the new opt-in value.
   * @param version the optimistic-lock version the caller last read; {@code null} bypasses the
   *     check, matching {@link #updateUserDefaultPayoutPreference}.
   * @return the persisted user with the updated flag and bumped version.
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown.
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale.
   */
  @Transactional
  public User updateUserShareBlueprintsGlobally(
      @NotNull UUID id, boolean shareBlueprintsGlobally, @Nullable Long version) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    user.setShareBlueprintsGlobally(shareBlueprintsGlobally);
    // saveAndFlush so the bumped @Version reaches the response — the profile blueprint-sharing
    // toggle writes the returned version back in place via syncAllVersions (no reload), so a stale
    // save version 409s the next consecutive change.
    return userRepository.saveAndFlush(user);
  }

  /**
   * Records that the user has read the given announcement (clears the unread badge on the home
   * page).
   *
   * @param id user id
   * @param announcementId announcement they read
   * @return the persisted user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user id is
   *     unknown
   */
  @Transactional
  public User updateReadAnnouncement(@NotNull UUID id, @NotNull UUID announcementId) {
    User user =
        userRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "User not found"));
    user.setLastReadAnnouncementId(announcementId);
    return userRepository.save(user);
  }

  /**
   * Returns all users sorted case-insensitively by username, scoped to the caller's squadron
   * context. Admin in "all squadrons" mode receives the cross-staffel list; everyone else sees the
   * members of <em>every</em> Staffel they belong to (REQ-ORG-017 — up to two), plus unassigned
   * admins/guests. Reads {@link OwnerScopeService#currentUserListScopeSquadronIds()} once per call.
   *
   * @return scoped user list, case-insensitively sorted by username
   */
  public List<User> findAll() {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findAllScopedList(
        scope, Sort.by(Sort.Order.asc("username").ignoreCase()));
  }

  /**
   * Returns paged user list, squadron-scoped (see {@link #findAll()}).
   *
   * @param pageable page request
   * @return scoped paged user list
   */
  public Page<User> findAll(@NotNull Pageable pageable) {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findAllScoped(scope, pageable);
  }

  /**
   * Returns paged squadron members eligible to be evaluated in the promotion system, scoped to the
   * caller's squadron context and excluding both admins and officers — the promotion system
   * assesses only the simple members of a squadron (issue #817). An Officer sees the ordinary
   * members of <em>every</em> Staffel they belong to (REQ-ORG-017 — up to two); an Admin in "all
   * squadrons" mode sees every squadron's ordinary members; an Admin/officer with the sidebar
   * switcher pinned to one Staffel sees that Staffel's ordinary members. Admins and officers
   * themselves are never returned — admins are squadron-less by design, and officers run the
   * Bewertungsverwaltung rather than being its subject. Delegates the filter to {@link
   * UserRepository#findEvaluatableMembers(java.util.Collection, Pageable)}.
   *
   * @param pageable page request
   * @return paged evaluatable members (squadron-scoped, admin- and officer-free)
   */
  @NotNull
  public Page<User> findEvaluatableMembers(@NotNull Pageable pageable) {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findEvaluatableMembers(scope, pageable);
  }

  /**
   * Returns lightweight reference projection used by typeaheads (id + username + displayName).
   * Squadron-scoped via {@link OwnerScopeService#currentUserListScopeSquadronIds()} — a non-admin
   * sees the members of every Staffel they belong to in pickers (REQ-ORG-017).
   *
   * @return lightweight reference projection used by typeaheads
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto> findAllReference() {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findAllReferenceScoped(scope);
  }

  /**
   * Unpaged username/displayName substring search, squadron-scoped (the union of the caller's
   * Staffeln, REQ-ORG-017).
   *
   * @param query free-text filter
   * @return matching users in the caller's squadron context
   */
  public List<User> searchByUsername(@NotNull String query) {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.searchScopedList(LikePatterns.escapeNullable(query), scope);
  }

  /**
   * Paged username/displayName substring search, squadron-scoped (the union of the caller's
   * Staffeln, REQ-ORG-017).
   *
   * @param query free-text filter
   * @param pageable page request
   * @return matching users in the caller's squadron context
   */
  public Page<User> searchByUsername(@NotNull String query, @NotNull Pageable pageable) {
    java.util.Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.searchScoped(LikePatterns.escapeNullable(query), scope, pageable);
  }

  /**
   * Returns the user.
   *
   * @param id user primary key
   * @return the user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  public User findById(@NotNull UUID id) {
    return userRepository
        .findById(id)
        .orElseThrow(
            () ->
                new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                    "User not found"));
  }

  /**
   * Looks up the calling user from the current {@link Authentication}. The single canonical
   * accessor for "who is calling" — other services delegate here instead of reaching for {@code
   * SecurityContextHolder} (the architectural seam enforced by ArchUnit).
   *
   * <p>Reads the subject through {@code AuthenticatedSubject}, so it answers for a bearer token and
   * for the token-less identity the ingest gateway installs alike (ADR-0129). Empty means "no
   * caller" — a guest; a caller whose subject is present but malformed is refused rather than
   * reported as absent.
   *
   * @return the calling user, or empty for unauthenticated requests
   * @throws org.springframework.security.authentication.AuthenticationServiceException if the
   *     caller's subject is not a UUID
   */
  public Optional<User> getCurrentUser() {
    // Asked of AuthenticatedSubject, not of the type. This is the canonical "who is calling"
    // accessor, and a Jwt-principal test made it answer "nobody" for an acting member (ADR-0129) —
    // latent today because neither ACTING_PATH reaches it, and an ownership check silently
    // evaluated against no current user the moment a third endpoint joins that list.
    //
    // NOT idOf(). That would fold "there is no caller" and "the caller's subject is malformed" into
    // the same empty Optional, and those must stay apart: the first is a guest, the second is a
    // misconfigured realm. Callers act on the difference — MissionService does
    // getCurrentUser().ifPresent(mission::setOwner), so a silent empty would persist an OWNERLESS
    // mission where this used to refuse the request outright.
    Optional<String> subject = AuthenticatedSubject.of(authHelperService.rawAuthentication());
    if (subject.isEmpty()) {
      return Optional.empty();
    }
    return userRepository.findById(requireUuidSubject(subject.get()));
  }

  /**
   * Parses a subject claim into a member id, refusing anything that is not a UUID.
   *
   * <p>The same fail-closed rule {@link #getUserIdFromJwt(Jwt)} applies, reached from the
   * token-less identity the ingest gateway installs (ADR-0129) as well as from a bearer token.
   * Deriving an id from a non-UUID subject — via {@code UUID.nameUUIDFromBytes} or otherwise —
   * would mix up identities across realms, so a deviation is refused rather than mapped.
   *
   * @param subject the caller's non-blank subject claim
   * @return the parsed member id
   * @throws AuthenticationServiceException if the subject is not a UUID
   */
  @NotNull
  private static UUID requireUuidSubject(@NotNull String subject) {
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException malformed) {
      // Deliberately without the value: it reaches the log unfiltered otherwise, and a subject from
      // a deviating realm can be a username (REQ-OBS-004).
      log.error("Authenticated subject is not a UUID. Refusing to avoid an identity mix-up.");
      throw new org.springframework.security.authentication.AuthenticationServiceException(
          "Authenticated subject must be a UUID");
    }
  }

  /**
   * SPEZIALKOMMANDO_PLAN.md §7.4 single-POST membership-delta orchestrator. Applies the supplied
   * Staffel + SK change set in one transaction so the admin member-edit page can persist every
   * change with one Save button click.
   *
   * <p>Resolution order matters and is fixed by this method:
   *
   * <ol>
   *   <li>Staffel side first — the {@code staffeln} list (the desired complete Staffel membership
   *       set, REQ-ORG-017 allows up to two) is reconciled against the current state by {@link
   *       OrgUnitMembershipService#reconcileStaffelMemberships}: squadrons are added / removed and
   *       per-squadron flags patched in one pass. A {@code null} list leaves the Staffel side
   *       untouched; a non-null (possibly empty) list is the authoritative target.
   *   <li>SK side second, in the order the client sent them. ADD adopts initial flags inline (no
   *       second {@code save}); REMOVE deletes by composite PK; PATCH validates the per-row
   *       {@code @Version} before writing. {@code is_lead} is intentionally not part of this
   *       payload — Lead toggles stay isolated in the SK detail page workflow per Plan D2 so the
   *       audit trail keeps clear per-toggle attribution.
   * </ol>
   *
   * <p>If any step throws (NotFoundException on a stale id, OptimisticLockingFailureException on a
   * stale version, DuplicateEntityException on an ADD for an existing membership,
   * BadRequestException on a Staffel-cardinality / leadership conflict) the entire transaction
   * rolls back — partial application is not exposed.
   *
   * @param userId the user whose memberships to mutate; never {@code null}.
   * @param delta the delta to apply; never {@code null}, but both halves may be {@code null} /
   *     empty (no-op delta is allowed and just returns the current state).
   * @return the user's complete post-write membership list (Staffel + every SK), never {@code
   *     null}.
   * @throws java.util.NoSuchElementException when the user does not exist.
   */
  @Transactional
  public java.util.List<de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership>
      applyMembershipDelta(
          UUID userId,
          de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest delta) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NoSuchElementException("User not found with id: " + userId));

    if (delta.staffeln() != null) {
      orgUnitMembershipService.reconcileStaffelMemberships(user, delta.staffeln());
    }
    if (delta.specialCommands() != null) {
      for (de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest
              .SpecialCommandChange
          sk : delta.specialCommands()) {
        applySpecialCommandChange(userId, sk);
      }
    }
    return orgUnitMembershipQueryService.findAllMembershipsForUser(userId);
  }

  /**
   * SK-side half of {@link #applyMembershipDelta}. Dispatches on the action discriminator and
   * forwards to the existing membership-service primitives. ADD adopts initial flags via a single
   * {@code save} on the freshly-created row (avoiding the intra-transaction double-version-bump
   * trap from CLAUDE.md "Concurrency" section); PATCH delegates to {@link
   * OrgUnitMembershipService#patchFlags} which does its own optimistic-lock check; REMOVE delegates
   * to {@link OrgUnitMembershipService#removeMember}.
   *
   * @param userId target user id.
   * @param change the SK-side change record.
   */
  private void applySpecialCommandChange(
      UUID userId,
      de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.SpecialCommandChange
          change) {
    switch (change.action()) {
      case ADD -> {
        de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership fresh =
            orgUnitMembershipService.addMember(change.orgUnitId(), userId);
        if (Boolean.TRUE.equals(change.isLogistician())
            || Boolean.TRUE.equals(change.isMissionManager())) {
          // The freshly-created row has version 0 and is still managed in this transaction.
          // Mutate it in place; Hibernate dirty-checking flushes the second update on commit
          // without a second explicit save call (avoiding the intra-transaction @Version race
          // documented in CLAUDE.md).
          if (Boolean.TRUE.equals(change.isLogistician())) {
            fresh.setLogistician(true);
          }
          if (Boolean.TRUE.equals(change.isMissionManager())) {
            fresh.setMissionManager(true);
          }
        }
      }
      case REMOVE -> orgUnitMembershipService.removeMember(change.orgUnitId(), userId);
      case PATCH ->
          orgUnitMembershipService.patchFlags(
              change.orgUnitId(),
              userId,
              new de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest(
                  change.isLogistician(), change.isMissionManager(), change.version()));
      default ->
          throw new IllegalArgumentException(
              "Unsupported SpecialCommandChange action: " + change.action());
    }
  }
}
