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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.support.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the local {@code app_user} mirror of Keycloak users: the caller-identity seam, the
 * squadron-scoped user reads, the self-service profile edits and the membership-delta orchestrator.
 *
 * <p>{@link #getUserIdFromJwt(Jwt)} and {@link #getCurrentUser()} are the canonical source of the
 * caller's id; subject parsing is fail-closed and rejects a missing or non-UUID {@code sub}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserService {

  /** The shape of an RSI handle: letters, digits, underscore and hyphen, 3 to 60 characters. */
  public static final Pattern RSI_HANDLE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,60}$");

  /** The i18n key of the refusal when another account already answers to the handle. */
  private static final String RSI_HANDLE_TAKEN = "error.user.rsiHandle.taken";

  private final UserRepository userRepository;
  private final AuthHelperService authHelperService;
  private final OwnerScopeService ownerScopeService;
  private final OrgUnitMembershipService orgUnitMembershipService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  /**
   * Checks whether any user has this exact name, case-insensitively, as username or display name.
   *
   * @param name candidate name
   * @return true when at least one match exists
   */
  public boolean isUsernameOrDisplayNameTaken(@NotNull String name) {
    return !findMatchesByExactName(name).isEmpty();
  }

  /**
   * Resolves a free-text participant name to users by case-insensitive exact match on {@code
   * username} or {@code displayName}. The input is trimmed; a blank name yields an empty list
   * without a query.
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
   * Extracts the user id from the JWT's {@code sub} claim, failing closed on a missing or non-UUID
   * value.
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
      log.error(
          "JWT has no subject (sub). Refusing the request. Claim keys: {}",
          jwt.getClaims().keySet());
      throw new AuthenticationServiceException("JWT subject (sub) must be present");
    }

    try {
      return UUID.fromString(sub);
    } catch (IllegalArgumentException e) {
      log.error(
          "JWT subject is not a valid UUID: '{}'. Refusing the request to avoid identity mix-up.",
          sub);
      throw new AuthenticationServiceException("JWT subject must be a UUID");
    }
  }

  /**
   * Updates a user's rank, description, display name and join date. The version is checked when
   * non-null; a {@code null} version (admin override) skips it. Rank must lie in the role's range
   * (officers 1–12, members 13–20); {@code joinDate} may be cleared with {@code null}, the other
   * fields change only when supplied.
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
      @Nullable LocalDate joinDate) {
    User user = Entities.require(userRepository.findById(id), "User not found");

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
      requireAssignableDisplayName(displayName, id);
      user.setDisplayName(displayName.isBlank() ? null : displayName);
    }
    user.setJoinDate(joinDate);
    return userRepository.save(user);
  }

  /**
   * Updates only the self-editable profile fields (description and display name).
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
    User user = Entities.require(userRepository.findById(id), "User not found");
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    if (description != null) {
      user.setDescription(description);
    }
    if (displayName != null) {
      requireAssignableDisplayName(displayName, id);
      user.setDisplayName(displayName.isBlank() ? null : displayName);
    }
    return userRepository.saveAndFlush(user);
  }

  /**
   * Sets the caller's default payout preference, pre-filled at mission sign-up ({@link
   * MissionService#addParticipant}). Existing participations are not changed.
   *
   * @param id the caller's id from the JWT; never {@code null}
   * @param preference the new default payout preference; never {@code null}
   * @param version the version the caller last read; {@code null} skips the check
   * @return the persisted user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public User updateUserDefaultPayoutPreference(
      @NotNull UUID id, @NotNull PayoutPreference preference, @Nullable Long version) {
    User user = Entities.require(userRepository.findById(id), "User not found");
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    user.setDefaultPayoutPreference(preference);
    return userRepository.saveAndFlush(user);
  }

  /**
   * Sets or clears the caller's RSI handle (REQ-SEC-072); a blank value clears it.
   *
   * <p>The handle is refused when another account already answers to it as username, display name
   * or RSI handle, because the erasure scrubs every spelling of a member (REQ-SEC-062). The handle
   * never appears in the error or in a log line.
   *
   * @param id the caller's id from the JWT
   * @param rsiHandle the new handle, already format-validated, or {@code null} / blank to clear it
   * @param version the version the caller last read; {@code null} skips the check
   * @return the persisted user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws BadRequestException when the handle does not have the shape of an RSI handle
   * @throws DuplicateEntityException when another account already answers to the handle
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public User updateUserRsiHandle(
      @NotNull UUID id, @Nullable String rsiHandle, @Nullable Long version) {
    User user = Entities.require(userRepository.findById(id), "User not found");
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    String candidate = rsiHandle == null ? "" : rsiHandle.trim();
    if (candidate.isEmpty()) {
      user.setRsiHandle(null);
      return userRepository.saveAndFlush(user);
    }
    if (!RSI_HANDLE_PATTERN.matcher(candidate).matches()) {
      throw new BadRequestException("error.user.rsiHandle.invalid");
    }
    if (userRepository.existsOtherAccountWithName(candidate.toLowerCase(Locale.ROOT), id)) {
      throw new DuplicateEntityException(RSI_HANDLE_TAKEN);
    }
    user.setRsiHandle(candidate);
    try {
      return userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException e) {
      throw new DuplicateEntityException(RSI_HANDLE_TAKEN);
    }
  }

  /**
   * Sets the caller's opt-in for global blueprint sharing, which counts their blueprints in the
   * availability views of every org unit (REQ-INV-018).
   *
   * @param id the caller's id from the JWT; never {@code null}
   * @param shareBlueprintsGlobally the new opt-in value
   * @param version the version the caller last read; {@code null} skips the check
   * @return the persisted user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the user is
   *     unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public User updateUserShareBlueprintsGlobally(
      @NotNull UUID id, boolean shareBlueprintsGlobally, @Nullable Long version) {
    User user = Entities.require(userRepository.findById(id), "User not found");
    OptimisticLock.checkOptionalClient(user.getVersion(), version, User.class, id);
    user.setShareBlueprintsGlobally(shareBlueprintsGlobally);
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
    User user = Entities.require(userRepository.findById(id), "User not found");
    user.setLastReadAnnouncementId(announcementId);
    return userRepository.save(user);
  }

  /**
   * Returns the users in the caller's squadron scope (REQ-ORG-017), sorted case-insensitively by
   * username; admins in "all squadrons" mode get every user.
   *
   * @return scoped user list, sorted by username
   */
  public List<User> findAll() {
    Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
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
    Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findAllScoped(scope, pageable);
  }

  /**
   * Returns the caller-scoped squadron members the promotion system evaluates, excluding admins and
   * officers; delegates to {@link UserRepository#findEvaluatableMembers(java.util.Collection,
   * Pageable)}.
   *
   * @param pageable page request
   * @return paged evaluatable members
   */
  @NotNull
  public Page<User> findEvaluatableMembers(@NotNull Pageable pageable) {
    Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.findEvaluatableMembers(scope, pageable);
  }

  /**
   * Returns lightweight reference projection used by typeaheads (id + username + displayName).
   * Squadron-scoped via {@link OwnerScopeService#currentUserListScopeSquadronIds()} — a non-admin
   * sees the members of every Staffel they belong to in pickers (REQ-ORG-017).
   *
   * @return lightweight reference projection used by typeaheads
   */
  public List<UserReferenceDto> findAllReference() {
    Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
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
    Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
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
    Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.searchScoped(LikePatterns.escapeNullable(query), scope, pageable);
  }

  /**
   * Squadron-scoped substring search projected to {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto}s for the user pickers,
   * without loading entities.
   *
   * @param query free-text filter; blank matches every user in scope
   * @param pageable page request
   * @return matching user references in the caller's squadron context
   */
  public Page<UserReferenceDto> searchReferencesByUsername(
      @NotNull String query, @NotNull Pageable pageable) {
    Set<UUID> scope = ownerScopeService.currentUserListScopeSquadronIds();
    return userRepository.searchScopedReferences(
        LikePatterns.escapeNullable(query), scope, pageable);
  }

  /**
   * Returns the user.
   *
   * @param id user primary key
   * @return the user
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  public User findById(@NotNull UUID id) {
    return Entities.require(userRepository.findById(id), "User not found");
  }

  /**
   * Looks up the calling user from the current {@link Authentication}, for bearer tokens and the
   * ingest gateway's token-less identity alike (ADR-0129).
   *
   * @return the calling user, or empty for unauthenticated requests
   * @throws org.springframework.security.authentication.AuthenticationServiceException if the
   *     caller's subject is not a UUID
   */
  public Optional<User> getCurrentUser() {
    Optional<String> subject = AuthenticatedSubject.of(authHelperService.rawAuthentication());
    if (subject.isEmpty()) {
      return Optional.empty();
    }
    return userRepository.findById(requireUuidSubject(subject.get()));
  }

  /**
   * Parses a subject claim into a member id, refusing anything that is not a UUID.
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
      log.error("Authenticated subject is not a UUID. Refusing to avoid an identity mix-up.");
      throw new AuthenticationServiceException("Authenticated subject must be a UUID");
    }
  }

  /**
   * Applies a Staffel and Spezialkommando membership change set in one transaction; any failure
   * rolls back the whole delta.
   *
   * <ol>
   *   <li>A non-null {@code staffeln} list is the target Staffel set, reconciled by {@link
   *       OrgUnitMembershipService#reconcileStaffelMemberships}; {@code null} leaves it untouched.
   *   <li>SK changes (ADD, REMOVE, PATCH) are then applied in request order; {@code is_lead} is not
   *       part of the payload.
   * </ol>
   *
   * @param userId the user whose memberships to change; never {@code null}
   * @param delta the delta to apply; never {@code null}, either half may be empty
   * @return the user's complete membership list after the write; never {@code null}
   * @throws java.util.NoSuchElementException when the user does not exist
   */
  @Transactional
  public List<OrgUnitMembership> applyMembershipDelta(UUID userId, MembershipDeltaRequest delta) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NoSuchElementException("User not found with id: " + userId));

    if (delta.staffeln() != null) {
      orgUnitMembershipService.reconcileStaffelMemberships(user, delta.staffeln());
    }
    if (delta.specialCommands() != null) {
      for (MembershipDeltaRequest.SpecialCommandChange sk : delta.specialCommands()) {
        applySpecialCommandChange(userId, sk);
      }
    }
    return orgUnitMembershipQueryService.findAllMembershipsForUser(userId);
  }

  /**
   * Applies one SK-side change of {@link #applyMembershipDelta}: ADD creates the row with its
   * initial flags in one save, PATCH delegates to {@link OrgUnitMembershipService#patchFlags},
   * REMOVE to {@link OrgUnitMembershipService#removeMember}.
   *
   * @param userId target user id
   * @param change the SK-side change record
   */
  private void applySpecialCommandChange(
      UUID userId, MembershipDeltaRequest.SpecialCommandChange change) {
    switch (change.action()) {
      case ADD -> {
        OrgUnitMembership fresh = orgUnitMembershipService.addMember(change.orgUnitId(), userId);
        if (Boolean.TRUE.equals(change.isLogistician())
            || Boolean.TRUE.equals(change.isMissionManager())) {
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
              new MembershipFlagsPatchRequest(
                  change.isLogistician(), change.isMissionManager(), change.version()));
      default ->
          throw new IllegalArgumentException(
              "Unsupported SpecialCommandChange action: " + change.action());
    }
  }

  /**
   * Rejects a display name that is the erasure sentinel or already another account's username or
   * display name, since the Art. 17 erasure matches on this field (REQ-SEC-062). Re-saving one's
   * own unchanged name is allowed.
   *
   * @param displayName the candidate; a blank one clears the field and never collides
   * @param selfId the account being edited
   * @throws IllegalArgumentException when the name is reserved or already somebody else's
   */
  private void requireAssignableDisplayName(@NotNull String displayName, @NotNull UUID selfId) {
    if (HandleAnonymisation.isReserved(displayName)) {
      throw new IllegalArgumentException("This display name is reserved");
    }
    String candidate = displayName.trim();
    if (candidate.isEmpty()) {
      return;
    }
    if (userRepository.existsOtherAccountWithName(candidate.toLowerCase(Locale.ROOT), selfId)) {
      throw new IllegalArgumentException("This display name is already in use");
    }
  }
}
