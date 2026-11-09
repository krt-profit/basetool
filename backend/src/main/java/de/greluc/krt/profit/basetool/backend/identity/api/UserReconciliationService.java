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

package de.greluc.krt.profit.basetool.backend.identity.api;

import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.events.MemberDepartedEvent;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the local {@code app_user} mirror with Keycloak: names, e-mail, Discord link, enabled
 * state and existence. Roles are held in {@code user_roles} alone and are never written from a
 * token or the roster (REQ-SEC-088, ADR-0355).
 *
 * <p>{@link #syncUser(Jwt)} runs on every authentication via {@code
 * CustomJwtGrantedAuthoritiesConverter}; {@link #syncUser(KeycloakUserDto)} and {@link
 * #markMissingUsers} are driven by {@code UserSyncTask} from the Admin API. New registrations are
 * stamped PENDING through {@link UserRegistrationService#stampNewPendingRegistration}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserReconciliationService {

  /** Width of the {@code discord_guild_nickname} column. */
  private static final int GUILD_NICKNAME_MAX_LENGTH = 255;

  private final UserRepository userRepository;
  private final MemberCreatedObserver memberCreatedObserver;
  private final ApplicationEventPublisher eventPublisher;
  private final UserRegistrationService userRegistrationService;
  private final UserService userService;
  private final LoginRoleGrant loginRoleGrant;

  /** Counts callsign collisions and auto-activated admin registrations. */
  private final MeterRegistry meterRegistry;

  /**
   * Reconciles the local user record with the supplied JWT on every authentication: creates the row
   * on first login and updates username, e-mail, Discord link and guild nickname when they changed.
   * The token's {@code realm_access} claim is ignored.
   *
   * <p>The lookup is by id only; a row holding the same username under another id is logged and
   * counted as a collision (ADR-0142). A bootstrap subject may earn Admin through {@link
   * LoginRoleGrant}; an Admin among the stored roles activates a pending registration.
   *
   * @param jwt validated JWT from the resource server
   * @return the managed (created or updated) user, whose stored roles authorise the request
   */
  @Transactional
  @NotNull
  public User syncUser(@NotNull Jwt jwt) {
    final UUID finalUserId = userService.getUserIdFromJwt(jwt);
    String username = jwt.getClaimAsString("preferred_username");

    final String discordUserId = jwt.getClaimAsString("discord_user_id");

    loginRoleGrant.lockFor(finalUserId);
    Optional<User> existingUser = userRepository.findById(finalUserId);
    if (existingUser.isEmpty() && username != null) {
      List<UUID> sameCallsign = userRepository.findIdsByUsername(username);
      if (!sameCallsign.isEmpty()) {
        meterRegistry.counter(IdentityMetricNames.USER_CALLSIGN_COLLISIONS).increment();
        log.warn(
            "Callsign collision: subject {} is unknown, but {} existing account(s) hold the same"
                + " preferred_username (value omitted, PII), e.g. {}. Provisioning a NEW pending"
                + " registration; nothing is inherited. An admin merges the two explicitly.",
            finalUserId,
            sameCallsign.size(),
            sameCallsign.getFirst());
      }
    }

    final boolean created = existingUser.isEmpty();

    User user =
        existingUser.orElseGet(() -> {
          User u = new User();
          u.setId(finalUserId);
          return u;
        });

    boolean changed = false;

    if (!user.isEnabledInKeycloak()) {
      user.setEnabledInKeycloak(true);
      changed = true;
    }

    if (!Objects.equals(user.getUsername(), username)) {
      user.setUsername(username);
      changed = true;
    }

    String email = jwt.getClaimAsString("email");
    if (!Objects.equals(user.getEmail(), email)) {
      user.setEmail(email);
      changed = true;
    }

    if (discordUserId != null
        && !discordUserId.isBlank()
        && applyDiscordLink(user, discordUserId)) {
      changed = true;
    }

    final boolean bootstrapAdmin = loginRoleGrant.grantIfDue(user);
    if (bootstrapAdmin) {
      changed = true;
    }

    String guildNickname = normalizeGuildNickname(jwt.getClaimAsString("discord_guild_nickname"));
    if (!Objects.equals(user.getDiscordGuildNickname(), guildNickname)) {
      user.setDiscordGuildNickname(guildNickname);
      changed = true;
    }

    boolean isAdmin = holdsAdmin(user.getRoles());
    boolean newPendingRegistration =
        userRegistrationService.stampNewPendingRegistration(user, created);
    if (newPendingRegistration) {
      changed = true;
    } else if (!created && isAdmin && user.getApprovalStatus() != ApprovalStatus.ACTIVE) {
      user.setApprovalStatus(ApprovalStatus.ACTIVE);
      user.setApprovedAt(Instant.now());
      changed = true;
    }
    if (created && isAdmin) {
      meterRegistry.counter(IdentityMetricNames.ADMIN_REGISTRATION_AUTO_ACTIVATED).increment();
    }

    if (changed || user.isNew()) {
      User saved = userRepository.save(user);
      if (bootstrapAdmin) {
        loginRoleGrant.recordGrant(saved);
      }
      if (created) {
        memberCreatedObserver.afterMemberCreated(user.getId());
      }
      if (newPendingRegistration) {
        eventPublisher.publishEvent(
            new DiscordRegistrationPendingEvent(saved.getId(), saved.getUsername()));
      }
      return saved;
    }

    return user;
  }

  /**
   * Reconciles the local user record from a Keycloak Admin API response (scheduled sync path):
   * existence, enabled state, username, e-mail and the Discord link. Used by {@code UserSyncTask}.
   *
   * <p>An active member disabled in Keycloak departs ({@link MemberDepartedEvent#REASON_DISABLED}).
   *
   * @param dto Keycloak admin DTO
   */
  @Transactional
  public void syncUser(@NotNull KeycloakUserDto dto) {
    if (dto.id() == null) {
      return;
    }

    Optional<User> existingUser = userRepository.findById(dto.id());
    final boolean created = existingUser.isEmpty();
    User user =
        existingUser.orElseGet(() -> {
          User u = new User();
          u.setId(dto.id());
          return u;
        });

    boolean changed = false;
    final boolean wasActive =
        !created && user.isInKeycloak() && user.isEnabledInKeycloak() && !user.getRoles().isEmpty();

    if (!user.isInKeycloak()) {
      user.setInKeycloak(true);
      user.setKeycloakAbsentSince(null);
      changed = true;
    }

    boolean enabled = !Boolean.FALSE.equals(dto.enabled());
    if (user.isEnabledInKeycloak() != enabled) {
      user.setEnabledInKeycloak(enabled);
      changed = true;
    }

    if (!Objects.equals(user.getUsername(), dto.username())) {
      user.setUsername(dto.username());
      changed = true;
    }

    if (!Objects.equals(user.getEmail(), dto.email())) {
      user.setEmail(dto.email());
      changed = true;
    }

    String discordUserId = dto.discordUserId();
    if (discordUserId != null
        && !discordUserId.isBlank()
        && applyDiscordLink(user, discordUserId)) {
      changed = true;
    }

    boolean newPendingRegistration =
        userRegistrationService.stampNewPendingRegistration(user, created);
    if (newPendingRegistration) {
      changed = true;
    }

    if (wasActive && !enabled) {
      eventPublisher.publishEvent(
          new MemberDepartedEvent(user.getId(), MemberDepartedEvent.REASON_DISABLED));
    }

    if (changed || user.isNew()) {
      userRepository.save(user);
      if (created) {
        memberCreatedObserver.afterMemberCreated(user.getId());
      }
      if (newPendingRegistration) {
        eventPublisher.publishEvent(
            new DiscordRegistrationPendingEvent(user.getId(), user.getUsername()));
      }
    }
  }

  /**
   * Writes the Discord snowflake onto {@code user} unless a different account already holds it, in
   * which case the write is skipped, counted and logged.
   *
   * <p>{@code app_user.discord_user_id} is unique, so a blind write would fail the whole
   * reconciliation of that user. The log names both account ids, never the snowflake (REQ-OBS-004).
   *
   * @param user the managed row to link; never {@code null}
   * @param discordUserId the snowflake to write; never {@code null} or blank
   * @return {@code true} when the link was written and the caller must persist, {@code false} when
   *     it was already present or another account holds it
   */
  private boolean applyDiscordLink(@NotNull User user, @NotNull String discordUserId) {
    if (Objects.equals(user.getDiscordUserId(), discordUserId)) {
      return false;
    }
    Optional<UUID> holder = userRepository.findIdByDiscordUserId(discordUserId);
    if (holder.isPresent() && !holder.get().equals(user.getId())) {
      meterRegistry.counter(IdentityMetricNames.USER_DISCORD_LINK_COLLISIONS).increment();
      log.warn(
          "Discord link not written for user {}: account {} already holds the same identity."
              + " Consolidate the two accounts; the link follows once the duplicate row is gone.",
          user.getId(),
          holder.get());
      return false;
    }
    user.setDiscordUserId(discordUserId);
    return true;
  }

  /**
   * Soft-deletes every local user whose id is not in {@code currentIds}; an empty set marks nobody,
   * so a Keycloak outage cannot soft-delete everyone.
   *
   * <p>{@code currentIds} must hold every id the Keycloak fetch reported, including users whose own
   * sync failed, or a transient failure soft-deletes a present member (REQ-SEC-043).
   *
   * @param currentIds the set of user ids currently present in Keycloak
   * @return the number of users newly flagged as missing; {@code 0} when {@code currentIds} is
   *     empty or nobody disappeared
   */
  @Transactional
  public int markMissingUsers(Collection<UUID> currentIds) {
    if (currentIds.isEmpty()) {
      return 0;
    }
    List<UUID> departing = userRepository.findIdsMissingFrom(currentIds);
    int flagged = userRepository.markMissingUsers(currentIds, Instant.now());
    departing.forEach(id ->
        eventPublisher.publishEvent(
            new MemberDepartedEvent(id, MemberDepartedEvent.REASON_REMOVED)));
    return flagged;
  }

  /**
   * Returns the ids of local users that already carry a Discord link, so the scheduled sync can
   * skip the per-user federated-identity read for them (incremental back-fill). Read-only.
   *
   * @return the ids of users with a non-null Discord link; never {@code null}, possibly empty.
   */
  @NotNull
  public Set<UUID> getKnownDiscordLinkedUserIds() {
    return userRepository.findIdsWithDiscordLink();
  }

  private static boolean holdsAdmin(@NotNull Set<Role> roles) {
    return roles.stream().anyMatch(role -> Roles.ADMIN.equalsIgnoreCase(role.getCode()));
  }

  /**
   * Normalises a raw Discord guild-nickname claim for storage: trims it, maps blank/empty to {@code
   * null}, and bounds it to the {@code discord_guild_nickname} column width so a pathologically
   * long attribute can never fail the login save. The Keycloak SPI already caps the captured value,
   * so this length bound is only a defensive backstop (REQ-DATA-018).
   *
   * @param raw the raw {@code discord_guild_nickname} claim value, possibly {@code null}
   * @return the trimmed, length-bounded nickname, or {@code null} when the claim is absent or blank
   */
  @Contract("null -> null")
  @Nullable
  private static String normalizeGuildNickname(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > GUILD_NICKNAME_MAX_LENGTH
        ? trimmed.substring(0, GUILD_NICKNAME_MAX_LENGTH)
        : trimmed;
  }
}
