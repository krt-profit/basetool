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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalRoleGrant;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.IdentityMetricNames;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakService;
import de.greluc.krt.profit.basetool.backend.identity.api.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.MemberCreatedObserver;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReconciliationService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRegistrationService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.events.MemberDepartedEvent;
import de.greluc.krt.profit.basetool.backend.identity.internal.BootstrapAdminGrant;
import de.greluc.krt.profit.basetool.backend.identity.internal.BootstrapAdminProperties;
import de.greluc.krt.profit.basetool.backend.identity.internal.RoleChangeRecorder;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitSeatNotifier;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link UserReconciliationService}, the Keycloak-to-local sync: {@link
 * UserReconciliationService#syncUser(Jwt)}, {@link
 * UserReconciliationService#syncUser(KeycloakUserDto)}, {@link
 * UserReconciliationService#markMissingUsers} and {@link
 * UserReconciliationService#getKnownDiscordLinkedUserIds}. Neither sync path writes a role
 * (REQ-SEC-088).
 *
 * <p>Uses a real {@link UserRegistrationService} and a mock {@link UserService} whose {@code
 * getUserIdFromJwt} parses the token subject.
 */
@ExtendWith(MockitoExtension.class)
class UserReconciliationServiceTest {

  /** A real registry: a mock cannot record a counter, and the assertions read one back. */
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Mock private UserRepository userRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private MemberCreatedObserver memberCreatedObserver;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private UserApprovalEventRepository userApprovalEventRepository;
  @Mock private UserService userService;
  @Mock private KeycloakService keycloakService;
  @Mock private UserDeletionService userDeletionService;
  @Mock private ObjectProvider<UserRegistrationService> selfProvider;
  @Mock private OrgUnitSeatNotifier seatNotifier;
  @Mock private RoleChangeRecorder roleChangeRecorder;
  @Mock private ApprovalRoleGrant approvalRoleGrant;

  /** The bootstrap subjects a test may extend; the record reads it by reference. */
  private final List<UUID> bootstrapSubjects = new ArrayList<>();

  private UserRegistrationService userRegistrationService;
  private UserReconciliationService userReconciliationService;

  private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  @BeforeEach
  void setUp() {
    userRegistrationService =
        new UserRegistrationService(
            userRepository,
            userApprovalEventRepository,
            eventPublisher,
            keycloakService,
            userDeletionService,
            approvalRoleGrant,
            selfProvider);
    setField(userRegistrationService, "requireApproval", true);
    userReconciliationService =
        new UserReconciliationService(
            userRepository,
            memberCreatedObserver,
            eventPublisher,
            userRegistrationService,
            userService,
            new BootstrapAdminGrant(
                new BootstrapAdminProperties(bootstrapSubjects),
                userRepository,
                roleRepository,
                roleChangeRecorder),
            meterRegistry);
    lenient()
        .when(userService.getUserIdFromJwt(any(Jwt.class)))
        .thenAnswer(inv -> UUID.fromString(((Jwt) inv.getArgument(0)).getSubject()));
  }

  /**
   * Verifies that the roster sync persists the Keycloak {@code enabled} flag, so deactivating a
   * member takes effect at the next sync (ADR-0129).
   */
  @Test
  void persistsTheKeycloakEnabledFlag() {
    User existing = new User();
    existing.setId(USER_ID);
    existing.setEnabledInKeycloak(true);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

    userReconciliationService.syncUser(
        new KeycloakUserDto(USER_ID, "alice", "alice@example.com", false, null));

    assertFalse(existing.isEnabledInKeycloak());
  }

  /**
   * A missing {@code enabled} field reads as enabled.
   *
   * <p>Fail-open on purpose, and the only place in this boundary where that is right: the flag can
   * only ever refuse, so a realm that stops sending the field would otherwise lock out the entire
   * member base at the next sync pass.
   */
  @Test
  void treatsAnAbsentEnabledFieldAsEnabled() {
    User existing = new User();
    existing.setId(USER_ID);
    existing.setEnabledInKeycloak(false);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

    userReconciliationService.syncUser(
        new KeycloakUserDto(USER_ID, "alice", "alice@example.com", null, null));

    assertTrue(existing.isEnabledInKeycloak());
  }

  /** The roster sync leaves the stored roles as they are. */
  @Test
  void theRosterSyncKeepsTheStoredRoles() {
    Role krtMember = codeRole("KRT_MEMBER", "KRT Member");
    User existing = new User();
    existing.setId(USER_ID);
    existing.setApprovalStatus(ApprovalStatus.ACTIVE);
    existing.setRoles(new HashSet<>(Set.of(krtMember)));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

    userReconciliationService.syncUser(
        new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, null));

    assertEquals(Set.of(krtMember), existing.getRoles());
    verify(roleRepository, never()).findAllWithPermissions();
    verify(seatNotifier, never()).onRolesChanged(any(), any());
  }

  @Nested
  class SyncJwtUserTests {

    @Test
    void createsNewUser_whenIdAndUsernameUnknown() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username", "alice",
                  "email", "alice@example.com"));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(jwt);

      assertEquals(USER_ID, result.getId());
      assertEquals("alice", result.getUsername());
      assertEquals("alice@example.com", result.getEmail());
      assertTrue(result.getRoles().isEmpty());
      verify(userRepository, times(1)).save(any(User.class));
      verify(memberCreatedObserver).afterMemberCreated(USER_ID);
    }

    /**
     * Verifies that an unknown subject becomes a new registration and never adopts an account
     * matched by callsign (ADR-0142). The assertion is on row identity, not on field values.
     */
    @Test
    void neverAdoptsAnAccountMatchedByCallsign_whenTheSubjectIsUnknown() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of("preferred_username", "alice"));
      UUID otherAccountId = UUID.randomUUID();

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of(otherAccountId));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(jwt);

      assertEquals(
          USER_ID,
          result.getId(),
          "the session must belong to the token's own subject, not to the callsign match");
      assertNotEquals(otherAccountId, result.getId());
      verify(userRepository, never()).findByUsername(any());
    }

    /** Verifies that a callsign collision increments its counter. */
    @Test
    void countsTheCallsignCollision_soTheHiddenCaseHasASignal() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of("preferred_username", "alice"));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of(UUID.randomUUID()));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);

      assertEquals(
          1.0,
          meterRegistry.counter(IdentityMetricNames.USER_CALLSIGN_COLLISIONS).count(),
          "a callsign collision must be counted");
    }

    /**
     * Verifies that a new row which is ACTIVE on arrival, a bootstrap subject earning Admin at its
     * first login and so carved out of the approval gate (REQ-SEC-017), is counted.
     */
    @Test
    void countsTheAutoActivatedAdmin_soTheCarveOutIsNotSilent() {
      bootstrapSubjects.add(USER_ID);
      when(roleRepository.lockByCode("ADMIN")).thenReturn(Optional.of(codeRole("ADMIN", "Admin")));
      Jwt jwt = newJwt(USER_ID.toString(), Map.of("preferred_username", "alice"));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);

      assertEquals(
          1.0,
          meterRegistry.counter(IdentityMetricNames.ADMIN_REGISTRATION_AUTO_ACTIVATED).count(),
          "an account that arrives already holding ADMIN must be counted");
    }

    /** A token claiming Admin does not make a new account an admin; it lands PENDING. */
    @Test
    void doesNotCountANewAccountWhoseTokenClaimsAdmin() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username",
                  "alice",
                  "realm_access",
                  Map.of("roles", List.of("Admin"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(jwt);

      assertEquals(ApprovalStatus.PENDING, result.getApprovalStatus());
      assertTrue(result.getRoles().isEmpty());
      assertEquals(
          0.0,
          meterRegistry.counter(IdentityMetricNames.ADMIN_REGISTRATION_AUTO_ACTIVATED).count());
    }

    /** No collision, no counter: an ordinary first login must not look like an incident. */
    @Test
    void doesNotCountAnythingForAnOrdinaryFirstLogin() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of("preferred_username", "alice"));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);

      assertEquals(
          0.0, meterRegistry.counter(IdentityMetricNames.USER_CALLSIGN_COLLISIONS).count());
    }

    @Test
    void noFieldChanged_andUserNotNew_skipsSave() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username", "alice",
                  "email", "alice@example.com"));

      User existing = newUser(USER_ID, "alice");
      existing.setEmail("alice@example.com");
      existing.setVersion(2L);
      existing.setRoles(new HashSet<>());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      User result = userReconciliationService.syncUser(jwt);

      assertSame(existing, result);
      verify(userRepository, never()).save(any(User.class));
      verify(memberCreatedObserver, never()).afterMemberCreated(any());
    }

    @Test
    void detectsChange_whenUsernameDiffers() {
      assertSavedOnFieldChange(
          "preferred_username", "new-username", User::setUsername, "old-username");
    }

    @Test
    void detectsChange_whenEmailDiffers() {
      assertSavedOnFieldChange("email", "new@example.com", User::setEmail, "old@example.com");
    }

    /** A realm-role claim neither changes the stored roles nor counts as a change. */
    @Test
    void aRealmRoleClaimLeavesTheStoredRolesAndSavesNothing() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username",
                  "alice",
                  "realm_access",
                  Map.of("roles", List.of("Admin", "Bank Management"))));
      Role krtMember = codeRole("KRT_MEMBER", "KRT Member");
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setApprovalStatus(ApprovalStatus.ACTIVE);
      existing.setRoles(new HashSet<>(Set.of(krtMember)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      User result = userReconciliationService.syncUser(jwt);

      assertEquals(Set.of(krtMember), result.getRoles());
      verify(userRepository, never()).save(any(User.class));
      verify(roleRepository, never()).findAllWithPermissions();
      verify(seatNotifier, never()).onRolesChanged(any(), any());
    }

    @Test
    void newUserWithNoChangedFields_isStillSaved() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(jwt);

      verify(userRepository, times(1)).save(result);
    }

    /**
     * Builds a JWT where every field already matches an existing user, then flips the named claim
     * to a different value and asserts that save is called.
     */
    private void assertSavedOnFieldChange(
        String jwtClaim,
        String newValue,
        java.util.function.BiConsumer<User, String> oldFieldSetter,
        String oldValue) {
      Map<String, Object> claims =
          new java.util.HashMap<>(
              Map.of(
                  "preferred_username", "alice",
                  "email", "alice@example.com"));
      claims.put(jwtClaim, newValue);
      Jwt jwt = newJwt(USER_ID.toString(), claims);

      User existing = newUser(USER_ID, "alice");
      existing.setEmail("alice@example.com");
      existing.setVersion(1L);
      oldFieldSetter.accept(existing, oldValue);
      existing.setRoles(new HashSet<>());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      lenient().when(userRepository.findIdsByUsername(any())).thenReturn(List.of());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);
      verify(userRepository, times(1)).save(any(User.class));
    }
  }

  @Nested
  class DiscordSyncTests {

    private static final String DISCORD_ID = "123456789012345678";

    @Test
    void newDiscordNonAdmin_landsPending_andNotifiesAdmins() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true));

      assertEquals(ApprovalStatus.PENDING, result.getApprovalStatus());
      assertEquals(DISCORD_ID, result.getDiscordUserId());
      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void newDiscordBootstrapAdmin_landsActive_noNotification() {
      bootstrapSubjects.add(USER_ID);
      when(roleRepository.lockByCode("ADMIN")).thenReturn(Optional.of(codeRole("ADMIN", "Admin")));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true));

      assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
      verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * Verifies that a new Discord login is never matched onto an existing row by {@code
     * preferred_username}, which is attacker-influenced: it lands as a new PENDING registration,
     * and the username collision is only logged and counted.
     */
    @Test
    void newDiscordLogin_ignoresMatchingCredentialUsername_landsPending() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true));

      assertEquals(USER_ID, result.getId());
      assertEquals(ApprovalStatus.PENDING, result.getApprovalStatus());
      assertEquals(DISCORD_ID, result.getDiscordUserId());
      verify(userRepository, never()).findByUsername(any());
      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void newPendingRegistration_notifiesAdmins_evenWithoutDiscordClaim() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("discorduser")).thenReturn(List.of());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false));

      assertEquals(ApprovalStatus.PENDING, result.getApprovalStatus());
      org.junit.jupiter.api.Assertions.assertNull(result.getDiscordUserId());
      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void existingPendingAdmin_isPromotedToActive() {
      User existing = new User();
      existing.setId(USER_ID);
      existing.setUsername("discorduser");
      existing.setApprovalStatus(ApprovalStatus.PENDING);
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>(Set.of(codeRole("ADMIN", "Admin"))));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false));

      assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void newNonAdmin_landsActive_whenApprovalNotRequired() {
      setField(userRegistrationService, "requireApproval", false);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("discorduser")).thenReturn(List.of());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false));

      assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void newDiscordLogin_persistsGuildNickname_trimmed() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      Jwt jwt =
          Jwt.withTokenValue("t")
              .header("alg", "none")
              .subject(USER_ID.toString())
              .claim("preferred_username", "discorduser")
              .claim("discord_user_id", DISCORD_ID)
              .claim("discord_guild_nickname", "  Vanguard Pilot  ")
              .build();

      User result = userReconciliationService.syncUser(jwt);

      assertEquals("Vanguard Pilot", result.getDiscordGuildNickname());
    }

    @Test
    void discordLoginWithoutNicknameClaim_leavesGuildNicknameNull() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true));

      org.junit.jupiter.api.Assertions.assertNull(result.getDiscordGuildNickname());
    }

    /**
     * Verifies that a Discord login whose snowflake is already held by another account skips the
     * UNIQUE {@code discord_user_id} write and counts it instead of failing the reconciliation.
     */
    @Test
    void discordLogin_whenAnotherAccountHoldsTheSnowflake_skipsTheLinkAndCountsIt() {
      User existing = newUser(USER_ID, "discorduser");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>());
      UUID otherAccount = UUID.fromString("11111111-2222-3333-4444-555555555555");

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(userRepository.findIdByDiscordUserId(DISCORD_ID)).thenReturn(Optional.of(otherAccount));

      userReconciliationService.syncUser(discordJwt(true));

      org.junit.jupiter.api.Assertions.assertNull(
          existing.getDiscordUserId(),
          "the link belongs to the other account until it is resolved");
      assertEquals(
          1.0,
          meterRegistry.counter(IdentityMetricNames.USER_DISCORD_LINK_COLLISIONS).count(),
          "the collision is counted so an alert can watch it");
    }

    /** Verifies that a Discord login with an unclaimed snowflake writes the link. */
    @Test
    void discordLogin_whenTheSnowflakeIsUnclaimed_writesTheLink() {
      User existing = newUser(USER_ID, "discorduser");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(userRepository.findIdByDiscordUserId(DISCORD_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(discordJwt(true));

      assertEquals(DISCORD_ID, existing.getDiscordUserId());
      assertEquals(
          0.0, meterRegistry.counter(IdentityMetricNames.USER_DISCORD_LINK_COLLISIONS).count());
    }

    private Jwt discordJwt(boolean withDiscord) {
      Jwt.Builder builder =
          Jwt.withTokenValue("t")
              .header("alg", "none")
              .subject(USER_ID.toString())
              .claim("preferred_username", "discorduser");
      if (withDiscord) {
        builder.claim("discord_user_id", DISCORD_ID);
      }
      return builder.build();
    }
  }

  @Nested
  class SyncKeycloakUserTests {

    /**
     * Verifies that the scheduled Admin-API backfill skips and counts a snowflake another account
     * already holds, keeping the rest of that user's reconciliation intact.
     */
    @Test
    void scheduledBackfill_whenAnotherAccountHoldsTheSnowflake_skipsTheLinkAndCountsIt() {
      UUID otherAccount = UUID.fromString("11111111-2222-3333-4444-555555555555");
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>());
      KeycloakUserDto dto =
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, "123456789012345678");

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(userRepository.findIdByDiscordUserId("123456789012345678"))
          .thenReturn(Optional.of(otherAccount));

      userReconciliationService.syncUser(dto);

      org.junit.jupiter.api.Assertions.assertNull(existing.getDiscordUserId());
      assertEquals(
          1.0, meterRegistry.counter(IdentityMetricNames.USER_DISCORD_LINK_COLLISIONS).count());
    }

    @Test
    void returnsEarly_whenDtoIdIsNull() {
      KeycloakUserDto dto = new KeycloakUserDto(null, "alice", "alice@example.com", true, null);

      userReconciliationService.syncUser(dto);

      verify(userRepository, never()).save(any());
    }

    @Test
    void flipsInKeycloak_whenLocalUserPreviouslyMarkedAbsent() {
      User existing = newUser(USER_ID, "alice");
      existing.setInKeycloak(false);
      existing.setVersion(1L);

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(new KeycloakUserDto(USER_ID, "alice", null, true, null));

      assertTrue(existing.isInKeycloak(), "must flip back to true once seen again");
      verify(userRepository, times(1)).save(existing);
    }

    @Test
    void createsNewUser_whenIdUnknown() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, null));

      verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void createsNewNonAdminUser_landsPending() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, null));

      verify(userRepository).save(argThat(u -> u.getApprovalStatus() == ApprovalStatus.PENDING));
    }

    @Test
    void createsNewNonAdminUser_notifiesAdmins() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, null));

      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void existingPendingUser_doesNotReNotify() {
      User existing = newUser(USER_ID, "alice");
      existing.setEmail("alice@example.com");
      existing.setInKeycloak(true);
      existing.setApprovalStatus(ApprovalStatus.PENDING);
      existing.setVersion(2L);
      existing.setRoles(new HashSet<>());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, null));

      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void noFieldChanged_andUserNotNew_skipsSave() {
      User existing = newUser(USER_ID, "alice");
      existing.setEmail("alice@example.com");
      existing.setInKeycloak(true);
      existing.setVersion(3L);
      existing.setRoles(new HashSet<>());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, null));

      verify(userRepository, never()).save(any());
    }

    @Test
    void backfillsDiscordLink_whenExistingUserLinkedLater() {
      User existing = newUser(USER_ID, "linkedlater");
      existing.setEmail("l@example.com");
      existing.setInKeycloak(true);
      existing.setVersion(4L);
      existing.setDiscordUserId(null);
      existing.setRoles(new HashSet<>());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "linkedlater", "l@example.com", true, "123456789012345678"));

      assertEquals("123456789012345678", existing.getDiscordUserId());
      verify(userRepository, times(1)).save(existing);
    }

    @Test
    void leavesExistingDiscordLink_whenDtoCarriesNoFederatedId() {
      User existing = newUser(USER_ID, "linked");
      existing.setEmail("l@example.com");
      existing.setInKeycloak(true);
      existing.setVersion(2L);
      existing.setDiscordUserId("123456789012345678");
      existing.setRoles(new HashSet<>());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "linked", "l@example.com", true, null));

      assertEquals("123456789012345678", existing.getDiscordUserId());
      verify(userRepository, never()).save(any());
    }
  }

  @Nested
  class MarkMissingUsersTests {

    @Test
    void emptyInput_doesNotCallRepository() {
      userReconciliationService.markMissingUsers(List.of());

      verify(userRepository, never()).markMissingUsers(any(), any());
    }

    @Test
    void nonEmptyInput_delegatesToRepository() {
      List<UUID> ids = List.of(USER_ID);

      userReconciliationService.markMissingUsers(ids);

      verify(userRepository).markMissingUsers(eq(ids), any());
    }

    @Test
    void stampsWhenTheAbsenceWasObserved() {
      Instant before = Instant.now();

      userReconciliationService.markMissingUsers(List.of(USER_ID));

      ArgumentCaptor<Instant> absentSince = ArgumentCaptor.forClass(Instant.class);
      verify(userRepository).markMissingUsers(any(), absentSince.capture());
      assertNotNull(absentSince.getValue());
      assertFalse(absentSince.getValue().isBefore(before));
      assertFalse(absentSince.getValue().isAfter(Instant.now()));
    }

    @Test
    void returnsTheRepositoryAffectedRowCount_soTheCallerCanReportIt() {
      List<UUID> ids = List.of(USER_ID);
      when(userRepository.markMissingUsers(eq(ids), any())).thenReturn(7);

      assertEquals(7, userReconciliationService.markMissingUsers(ids));
    }

    @Test
    void emptyInput_reportsZeroFlagged() {
      assertEquals(0, userReconciliationService.markMissingUsers(List.of()));
    }
  }

  @Nested
  class SyncInputCatalogTests {

    @Test
    void getKnownDiscordLinkedUserIds_returnsAlreadyLinkedIds() {
      UUID linked = UUID.randomUUID();
      when(userRepository.findIdsWithDiscordLink()).thenReturn(Set.of(linked));

      assertEquals(Set.of(linked), userReconciliationService.getKnownDiscordLinkedUserIds());
      verify(userRepository).findIdsWithDiscordLink();
    }
  }

  private static Jwt newJwt(String subject, Map<String, Object> additionalClaims) {
    Map<String, Object> claims = new java.util.HashMap<>();
    claims.put("sub", subject);
    claims.putAll(additionalClaims);
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claims(c -> c.putAll(claims))
        .build();
  }

  private static User newUser(UUID id, String username) {
    User u = new User();
    u.setId(id);
    u.setUsername(username);
    return u;
  }

  private static Role role(long id, String name) {
    Role r = new Role();
    r.setId(id);
    r.setName(name);
    return r;
  }

  private static Role codeRole(String code, String name) {
    Role r = new Role();
    r.setCode(code);
    r.setName(name);
    return r;
  }

  @Nested
  class DepartureTests {

    @Test
    void anActiveMemberDisabledInKeycloakDeparts() {
      User existing = newUser(USER_ID, "alice");
      existing.setEnabledInKeycloak(true);
      existing.setRoles(new HashSet<>(Set.of(role(2L, "KRT Member"))));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", false, null));

      verify(eventPublisher)
          .publishEvent(new MemberDepartedEvent(USER_ID, MemberDepartedEvent.REASON_DISABLED));
    }

    @Test
    void anActiveMemberSeenByTheRosterSyncDoesNotDepart() {
      User existing = newUser(USER_ID, "alice");
      existing.setEnabledInKeycloak(true);
      existing.setRoles(new HashSet<>(Set.of(role(2L, "KRT Member"))));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, null));

      verify(eventPublisher, never()).publishEvent(any(MemberDepartedEvent.class));
    }

    @Test
    void anAccountThatWasNeverActiveDoesNotDepart() {
      User existing = newUser(USER_ID, "alice");
      existing.setEnabledInKeycloak(true);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", false, null));

      verify(eventPublisher, never()).publishEvent(any(MemberDepartedEvent.class));
    }

    @Test
    void aMemberLoggingInWithoutARoleClaimKeepsTheRolesAndDoesNotDepart() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>(Set.of(role(2L, "KRT Member"))));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          newJwt(USER_ID.toString(), Map.of("preferred_username", "alice")));

      assertEquals(1, existing.getRoles().size());
      verify(eventPublisher, never()).publishEvent(any(MemberDepartedEvent.class));
    }

    @Test
    void everyAccountNoLongerInKeycloakDeparts() {
      UUID gone = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-000000000001");
      List<UUID> present = List.of(USER_ID);
      when(userRepository.findIdsMissingFrom(present)).thenReturn(List.of(gone));

      userReconciliationService.markMissingUsers(present);

      verify(eventPublisher)
          .publishEvent(new MemberDepartedEvent(gone, MemberDepartedEvent.REASON_REMOVED));
    }
  }

  @Nested
  class BootstrapAdminTests {

    private final Role admin = codeRole("ADMIN", "Admin");

    private User loginOfAnExistingMember() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setApprovalStatus(ApprovalStatus.PENDING);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
      userReconciliationService.syncUser(
          newJwt(USER_ID.toString(), Map.of("preferred_username", "alice")));
      return existing;
    }

    @Test
    void aBootstrapSubjectReceivesAdminWhileNoActiveAdminExists() {
      bootstrapSubjects.add(USER_ID);
      when(roleRepository.lockByCode("ADMIN")).thenReturn(Optional.of(admin));
      when(userRepository.countOtherActiveHolders("ADMIN", USER_ID)).thenReturn(0L);

      User member = loginOfAnExistingMember();

      assertTrue(member.getRoles().contains(admin));
      assertEquals(ApprovalStatus.ACTIVE, member.getApprovalStatus());
      verify(userRepository).bumpRolesVersion(USER_ID);
      verify(roleChangeRecorder).bootstrapGranted(member);
    }

    @Test
    void aBootstrapSubjectReceivesNothingWhileAnotherActiveAdminExists() {
      bootstrapSubjects.add(USER_ID);
      when(roleRepository.lockByCode("ADMIN")).thenReturn(Optional.of(admin));
      when(userRepository.countOtherActiveHolders("ADMIN", USER_ID)).thenReturn(1L);

      User member = loginOfAnExistingMember();

      assertFalse(member.getRoles().contains(admin));
      verify(roleChangeRecorder, never()).bootstrapGranted(any());
    }

    @Test
    void anUnlistedSubjectReceivesNothing() {
      User member = loginOfAnExistingMember();

      assertTrue(member.getRoles().isEmpty());
      verify(roleRepository, never()).lockByCode(any());
      verify(roleChangeRecorder, never()).bootstrapGranted(any());
    }
  }
}
