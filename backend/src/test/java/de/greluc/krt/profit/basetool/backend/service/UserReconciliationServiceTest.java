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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.event.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserApprovalEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.PartialRoleScopeProperties;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link UserReconciliationService}, the Keycloak-to-local sync: {@link
 * UserReconciliationService#syncUser(Jwt)}, {@link
 * UserReconciliationService#syncUser(KeycloakUserDto)}, {@link
 * UserReconciliationService#markMissingUsers}, the role mapping ({@link
 * UserReconciliationService#extractRolesFromJwt}) and the sync-input catalogs ({@link
 * UserReconciliationService#getMappableRoleNames} / {@link
 * UserReconciliationService#getKnownDiscordLinkedUserIds}).
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
  @Mock private DefaultBlueprintProvisioningService defaultBlueprintProvisioningService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private UserApprovalEventRepository userApprovalEventRepository;
  @Mock private UserService userService;
  @Mock private KeycloakService keycloakService;
  @Mock private UserDeletionService userDeletionService;
  @Mock private ObjectProvider<UserRegistrationService> selfProvider;

  private UserRegistrationService userRegistrationService;
  private UserReconciliationService userReconciliationService;

  /** The partial-scope allowlist a test may extend; the record reads it by reference. */
  private final List<String> partialScopeClientIds = new ArrayList<>();

  /**
   * A real instance, empty by default so tests see complete-claim behaviour; the REQ-SEC-036 cases
   * populate it.
   */
  private final PartialRoleScopeProperties partialRoleScopeProperties =
      new PartialRoleScopeProperties(partialScopeClientIds);

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
            selfProvider);
    setField(userRegistrationService, "requireApproval", true);
    userReconciliationService =
        new UserReconciliationService(
            userRepository,
            roleRepository,
            defaultBlueprintProvisioningService,
            eventPublisher,
            userRegistrationService,
            userService,
            partialRoleScopeProperties,
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
        new KeycloakUserDto(USER_ID, "alice", "alice@example.com", false, Set.of(), null));

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
        new KeycloakUserDto(USER_ID, "alice", "alice@example.com", null, Set.of(), null));

    assertTrue(existing.isEnabledInKeycloak());
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

      User result = userReconciliationService.syncUser(jwt).user();

      assertEquals(USER_ID, result.getId());
      assertEquals("alice", result.getUsername());
      assertEquals("alice@example.com", result.getEmail());
      assertTrue(result.getRoles().isEmpty());
      verify(userRepository, times(1)).save(any(User.class));
      verify(defaultBlueprintProvisioningService).grantDefaultsToUser(USER_ID);
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

      User result = userReconciliationService.syncUser(jwt).user();

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
          meterRegistry.counter(MetricNames.USER_CALLSIGN_COLLISIONS).count(),
          "a callsign collision must be counted");
    }

    /**
     * Verifies that a new row which is ACTIVE on arrival, an ADMIN realm-role holder carved out of
     * the approval gate (REQ-SEC-017), is counted.
     */
    @Test
    void countsTheAutoActivatedAdmin_soTheCarveOutIsNotSilent() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username",
                  "alice",
                  "realm_access",
                  Map.of("roles", List.of("ADMIN"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of());
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(codeRole("ADMIN", "ADMIN")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);

      assertEquals(
          1.0,
          meterRegistry.counter(MetricNames.ADMIN_REGISTRATION_AUTO_ACTIVATED).count(),
          "an account that arrives already holding ADMIN must be counted");
    }

    /** A new ordinary member lands PENDING, which is the approval queue's own signal, not this. */
    @Test
    void doesNotCountANewOrdinaryMemberAsAnAutoActivatedAdmin() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username",
                  "alice",
                  "realm_access",
                  Map.of("roles", List.of("MEMBER"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of());
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(codeRole("MEMBER", "MEMBER")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);

      assertEquals(
          0.0, meterRegistry.counter(MetricNames.ADMIN_REGISTRATION_AUTO_ACTIVATED).count());
    }

    /** No collision, no counter: an ordinary first login must not look like an incident. */
    @Test
    void doesNotCountAnythingForAnOrdinaryFirstLogin() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of("preferred_username", "alice"));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);

      assertEquals(0.0, meterRegistry.counter(MetricNames.USER_CALLSIGN_COLLISIONS).count());
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

      User result = userReconciliationService.syncUser(jwt).user();

      assertSame(existing, result);
      verify(userRepository, never()).save(any(User.class));
      verify(defaultBlueprintProvisioningService, never()).grantDefaultsToUser(any());
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

    @Test
    void detectsChange_whenRolesDiffer() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username",
                  "alice",
                  "realm_access",
                  Map.of("roles", List.of("ADMIN"))));

      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(role(1L, "ADMIN")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(jwt);

      verify(userRepository, times(1)).save(any(User.class));
      assertEquals(1, existing.getRoles().size());
      assertEquals("ADMIN", existing.getRoles().iterator().next().getName());
    }

    @Test
    void newUserWithNoChangedFields_isStillSaved() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of());

      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(jwt).user();

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

      User result = userReconciliationService.syncUser(discordJwt(true, List.of())).user();

      assertEquals(ApprovalStatus.PENDING, result.getApprovalStatus());
      assertEquals(DISCORD_ID, result.getDiscordUserId());
      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void newDiscordAdmin_landsActive_noNotification() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(codeRole("ADMIN", "Admin")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true, List.of("Admin"))).user();

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

      User result = userReconciliationService.syncUser(discordJwt(true, List.of())).user();

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

      User result = userReconciliationService.syncUser(discordJwt(false, List.of())).user();

      assertEquals(ApprovalStatus.PENDING, result.getApprovalStatus());
      org.junit.jupiter.api.Assertions.assertNull(result.getDiscordUserId());
      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void newCredentialAdmin_landsActive_noNotification() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("discorduser")).thenReturn(List.of());
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(codeRole("ADMIN", "Admin")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false, List.of("Admin"))).user();

      assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void existingPendingAdmin_isPromotedToActive() {
      User existing = new User();
      existing.setId(USER_ID);
      existing.setUsername("discorduser");
      existing.setApprovalStatus(ApprovalStatus.PENDING);
      existing.setVersion(1L);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(codeRole("ADMIN", "Admin")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false, List.of("Admin"))).user();

      assertEquals(ApprovalStatus.ACTIVE, result.getApprovalStatus());
      verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void newNonAdmin_landsActive_whenApprovalNotRequired() {
      setField(userRegistrationService, "requireApproval", false);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("discorduser")).thenReturn(List.of());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(false, List.of())).user();

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
              .claim("realm_access", Map.of("roles", List.of()))
              .claim("discord_user_id", DISCORD_ID)
              .claim("discord_guild_nickname", "  Vanguard Pilot  ")
              .build();

      User result = userReconciliationService.syncUser(jwt).user();

      assertEquals("Vanguard Pilot", result.getDiscordGuildNickname());
    }

    @Test
    void discordLoginWithoutNicknameClaim_leavesGuildNicknameNull() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      User result = userReconciliationService.syncUser(discordJwt(true, List.of())).user();

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

      userReconciliationService.syncUser(discordJwt(true, List.of()));

      org.junit.jupiter.api.Assertions.assertNull(
          existing.getDiscordUserId(),
          "the link belongs to the other account until it is resolved");
      assertEquals(
          1.0,
          meterRegistry.counter(MetricNames.USER_DISCORD_LINK_COLLISIONS).count(),
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

      userReconciliationService.syncUser(discordJwt(true, List.of()));

      assertEquals(DISCORD_ID, existing.getDiscordUserId());
      assertEquals(0.0, meterRegistry.counter(MetricNames.USER_DISCORD_LINK_COLLISIONS).count());
    }

    private Jwt discordJwt(boolean withDiscord, List<String> realmRoles) {
      Jwt.Builder builder =
          Jwt.withTokenValue("t")
              .header("alg", "none")
              .subject(USER_ID.toString())
              .claim("preferred_username", "discorduser")
              .claim("realm_access", Map.of("roles", realmRoles));
      if (withDiscord) {
        builder.claim("discord_user_id", DISCORD_ID);
      }
      return builder.build();
    }
  }

  /**
   * Tests for tokens from a partial-scope client such as the mobile app (REQ-SEC-035), whose role
   * claim omits roles the member holds.
   *
   * <p>The stored role set must survive such a claim, and the request must still be authorised by
   * it. The fixtures use {@code Admin} as the omitted role.
   */
  @Nested
  class PartialRoleScopeTests {

    private static final String MOBILE_CLIENT = "basetool-android";

    /** Lists the mobile client, which every case in this class assumes is configured as partial. */
    @BeforeEach
    void listTheMobileClient() {
      partialScopeClientIds.add(MOBILE_CLIENT);
    }

    /**
     * Builds a token for {@code azp} carrying exactly {@code realmRoles}.
     *
     * @param azp the authorized-party claim naming the client that requested the token
     * @param realmRoles the realm-role names the client's scope let through
     * @return the token
     */
    private Jwt tokenFrom(String azp, List<String> realmRoles) {
      return newJwt(
          USER_ID.toString(),
          Map.of(
              "preferred_username",
              "alice",
              "azp",
              azp,
              "realm_access",
              Map.of("roles", realmRoles)));
    }

    /**
     * Verifies that a partial-scope claim does not overwrite the stored roles, so an administrator
     * logging in through the app keeps {@code Admin}.
     */
    @Test
    void doesNotOverwriteTheStoredRoles_whenTheClaimComesFromAPartialScopeClient() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      Role admin = role(1L, "Admin");
      Role member = role(2L, "KRT Member");
      existing.setRoles(new HashSet<>(Set.of(admin, member)));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findAllWithPermissions()).thenReturn(java.util.List.of(member));

      userReconciliationService.syncUser(tokenFrom(MOBILE_CLIENT, List.of("KRT Member")));

      assertEquals(
          Set.of("Admin", "KRT Member"),
          roleNames(existing.getRoles()),
          "the stored role set must survive a claim that could not have carried Admin");
    }

    /**
     * The request is nevertheless authorised with the token's roles, not the row's.
     *
     * <p>Without this the guard would be worse than the defect: the row keeps {@code Admin}
     * precisely because the app path stopped overwriting it, so authorising from the row would hand
     * the app the very authority its client scope withholds.
     */
    @Test
    void returnsTheTokensRolesAsEffective_whenTheClaimIsPartial() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>(Set.of(role(1L, "Admin"), role(2L, "KRT Member"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(role(2L, "KRT Member")));

      UserReconciliationService.ReconciledUser reconciled =
          userReconciliationService.syncUser(tokenFrom(MOBILE_CLIENT, List.of("KRT Member")));

      assertEquals(
          Set.of("KRT Member"),
          roleNames(reconciled.effectiveRoles()),
          "the request must carry only what the token presented");
    }

    /**
     * A client that is NOT listed still replaces the stored set - including shrinking it.
     *
     * <p>The complement that keeps the guard honest: one that stopped every role removal would mean
     * a demotion in Keycloak never reached the database, which is its own privilege defect.
     */
    @Test
    void stillReplacesTheStoredRoles_whenTheClaimComesFromAnOrdinaryClient() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>(Set.of(role(1L, "Admin"), role(2L, "KRT Member"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(role(2L, "KRT Member")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(tokenFrom("basetool-frontend", List.of("KRT Member")));

      assertEquals(
          Set.of("KRT Member"),
          roleNames(existing.getRoles()),
          "a complete claim must still be able to remove a role");
    }

    /**
     * Verifies that a partial-scope claim does persist its roles when it creates the row, since
     * there is no stored set to protect and an empty one would refuse the account (REQ-SEC-053).
     */
    @Test
    void persistsTheRoles_whenThePartialClaimCreatesTheRow() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findIdsByUsername("alice")).thenReturn(List.of());
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(role(2L, "KRT Member")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      UserReconciliationService.ReconciledUser reconciled =
          userReconciliationService.syncUser(tokenFrom(MOBILE_CLIENT, List.of("KRT Member")));

      assertEquals(
          Set.of("KRT Member"),
          roleNames(reconciled.user().getRoles()),
          "a new row must not be created role-less");
    }

    /**
     * A token with no {@code azp} is never treated as partial.
     *
     * <p>An absent claim must fail towards the established behaviour, not towards the exception:
     * treating "unknown client" as partial would quietly stop every role change from any issuer
     * that omits the claim.
     */
    @Test
    void treatsAMissingAzpAsAnOrdinaryClient() {
      User existing = newUser(USER_ID, "alice");
      existing.setVersion(1L);
      existing.setRoles(new HashSet<>(Set.of(role(1L, "Admin"))));

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(roleRepository.findAllWithPermissions())
          .thenReturn(java.util.List.of(role(2L, "KRT Member")));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      Jwt noAzp =
          newJwt(
              USER_ID.toString(),
              Map.of(
                  "preferred_username",
                  "alice",
                  "realm_access",
                  Map.of("roles", List.of("KRT Member"))));

      userReconciliationService.syncUser(noAzp);

      assertEquals(Set.of("KRT Member"), roleNames(existing.getRoles()));
    }

    /**
     * Collapses a role set to its names, so a failure message names the roles rather than printing
     * entity identity hashes.
     *
     * @param roles the roles to name
     * @return their names
     */
    private Set<String> roleNames(java.util.Collection<Role> roles) {
      return roles.stream().map(Role::getName).collect(java.util.stream.Collectors.toSet());
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
          new KeycloakUserDto(
              USER_ID, "alice", "alice@example.com", true, Set.of(), "123456789012345678");

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
      when(userRepository.findIdByDiscordUserId("123456789012345678"))
          .thenReturn(Optional.of(otherAccount));

      userReconciliationService.syncUser(dto);

      org.junit.jupiter.api.Assertions.assertNull(existing.getDiscordUserId());
      assertEquals(1.0, meterRegistry.counter(MetricNames.USER_DISCORD_LINK_COLLISIONS).count());
    }

    @Test
    void returnsEarly_whenDtoIdIsNull() {
      KeycloakUserDto dto =
          new KeycloakUserDto(null, "alice", "alice@example.com", true, Set.of(), null);

      userReconciliationService.syncUser(dto);

      verify(userRepository, never()).save(any());
    }

    @Test
    void flipsInKeycloak_whenLocalUserPreviouslyMarkedAbsent() {
      User existing = newUser(USER_ID, "alice");
      existing.setInKeycloak(false);
      existing.setVersion(1L);

      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", null, true, Set.of(), null));

      assertTrue(existing.isInKeycloak(), "must flip back to true once seen again");
      verify(userRepository, times(1)).save(existing);
    }

    @Test
    void createsNewUser_whenIdUnknown() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

      verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void createsNewNonAdminUser_landsPending() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

      verify(userRepository).save(argThat(u -> u.getApprovalStatus() == ApprovalStatus.PENDING));
    }

    @Test
    void createsNewAdminUser_landsActive() {
      Role adminRole = role(1L, "ADMIN");
      adminRole.setCode("ADMIN");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findAllWithPermissions()).thenReturn(java.util.List.of(adminRole));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "root", null, true, Set.of("ADMIN"), null));

      verify(userRepository).save(argThat(u -> u.getApprovalStatus() == ApprovalStatus.ACTIVE));
    }

    @Test
    void createsNewNonAdminUser_notifiesAdmins() {
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

      verify(eventPublisher).publishEvent(any(DiscordRegistrationPendingEvent.class));
    }

    @Test
    void createsNewAdminUser_doesNotNotify() {
      Role adminRole = role(1L, "ADMIN");
      adminRole.setCode("ADMIN");
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(roleRepository.findAllWithPermissions()).thenReturn(java.util.List.of(adminRole));
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

      userReconciliationService.syncUser(
          new KeycloakUserDto(USER_ID, "root", null, true, Set.of("ADMIN"), null));

      verify(eventPublisher, never()).publishEvent(any());
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
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

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
          new KeycloakUserDto(USER_ID, "alice", "alice@example.com", true, Set.of(), null));

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
          new KeycloakUserDto(
              USER_ID, "linkedlater", "l@example.com", true, Set.of(), "123456789012345678"));

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
          new KeycloakUserDto(USER_ID, "linked", "l@example.com", true, Set.of(), null));

      assertEquals("123456789012345678", existing.getDiscordUserId());
      verify(userRepository, never()).save(any());
    }
  }

  @Nested
  class ExtractRolesFromJwtTests {

    @Test
    void returnsRoles_whenRealmAccessHasRolesKey() {
      Jwt jwt =
          newJwt(
              USER_ID.toString(),
              Map.of("realm_access", Map.of("roles", List.of("ADMIN", "KRT_MEMBER"))));

      Set<String> roles = userReconciliationService.extractRolesFromJwt(jwt);

      assertEquals(Set.of("ADMIN", "KRT_MEMBER"), roles);
    }

    @Test
    void returnsEmpty_whenRealmAccessClaimMissing() {
      Jwt jwt = newJwt(USER_ID.toString(), Map.of());

      assertTrue(userReconciliationService.extractRolesFromJwt(jwt).isEmpty());
    }

    @Test
    void returnsEmpty_whenRealmAccessLacksRolesKey() {
      Jwt jwt =
          newJwt(USER_ID.toString(), Map.of("realm_access", Map.of("something_else", "value")));

      assertTrue(userReconciliationService.extractRolesFromJwt(jwt).isEmpty());
    }
  }

  /**
   * Verifies that a token whose realm roles map to nothing yields an account with no roles, which
   * is then refused as {@code NO_ROLE} (REQ-SEC-053, ADR-0159).
   */
  @Test
  void mapRoles_writesNoRole_whenNoKeycloakRoleMatchesLocal() {
    Jwt jwt =
        newJwt(
            USER_ID.toString(),
            Map.of(
                "preferred_username",
                "alice",
                "realm_access",
                Map.of("roles", List.of("UNKNOWN_ROLE_FROM_OTHER_REALM"))));

    when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
    when(userRepository.findIdsByUsername("alice")).thenReturn(List.of());
    when(roleRepository.findAllWithPermissions()).thenReturn(List.of());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User result = userReconciliationService.syncUser(jwt).user();

    assertTrue(result.getRoles().isEmpty(), "an unmatched realm role grants nothing");
  }

  @Test
  void mapRoles_nullRoleNames_alsoWriteNoRole() {
    when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    userReconciliationService.syncUser(
        new KeycloakUserDto(USER_ID, "alice", null, true, null, null));

    org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(saved.capture());
    assertTrue(saved.getValue().getRoles().isEmpty(), "a null role list grants nothing");
    verify(roleRepository, never()).findAllWithPermissions();
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
  class RoleSyncSummaryTests {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
      logger = (Logger) LoggerFactory.getLogger(UserReconciliationService.class);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
      logger.detachAppender(appender);
    }

    @Test
    void quietRun_logsTheAggregateAtInfo() {
      userReconciliationService.logRoleSyncSummary();

      ILoggingEvent event = onlyEvent();
      assertEquals(Level.INFO, event.getLevel());
      assertTrue(event.getFormattedMessage().contains("0 accounts changed roles"));
    }

    @Test
    void aMassRoleStrip_escalatesToWarn_withCountsOnly() {
      when(roleRepository.findAllWithPermissions()).thenReturn(List.of());
      for (int i = 0; i < 4; i++) {
        syncDemotedAccount();
      }

      userReconciliationService.logRoleSyncSummary();

      ILoggingEvent event = lastEvent();
      assertEquals(Level.WARN, event.getLevel());
      String message = event.getFormattedMessage();
      assertTrue(message.contains("4 accounts changed roles"), message);
      assertTrue(
          message.contains("4 accounts resolve to NO role at all and are refused with NO_ROLE"),
          message);
      assertFalse(message.contains("demoted-callsign"), message);
    }

    @Test
    void aSingleRoleStrip_staysAtInfo_andTheTalliesResetForTheNextRun() {
      when(roleRepository.findAllWithPermissions()).thenReturn(List.of());
      syncDemotedAccount();

      userReconciliationService.logRoleSyncSummary();
      assertEquals(Level.INFO, lastEvent().getLevel());
      assertTrue(lastEvent().getFormattedMessage().contains("1 accounts changed roles"));

      userReconciliationService.logRoleSyncSummary();
      assertEquals(Level.INFO, lastEvent().getLevel());
      assertTrue(lastEvent().getFormattedMessage().contains("0 accounts changed roles"));
    }

    @Test
    void anAlreadyRoleLessAccountStillCounts_becauseTheTallyIsACensusNotADelta() {
      when(roleRepository.findAllWithPermissions()).thenReturn(List.of());
      for (int i = 0; i < 4; i++) {
        UUID id = UUID.randomUUID();
        User existing = newUser(id, "already-role-less");
        existing.setInKeycloak(true);
        existing.setApprovalStatus(ApprovalStatus.ACTIVE);
        existing.setRoles(new HashSet<>());
        when(userRepository.findById(id)).thenReturn(Optional.of(existing));
        userReconciliationService.syncUser(
            new KeycloakUserDto(id, "already-role-less", null, true, Set.of("Officer"), null));
      }

      userReconciliationService.logRoleSyncSummary();

      ILoggingEvent event = lastEvent();
      assertEquals(Level.WARN, event.getLevel());
      assertTrue(
          event.getFormattedMessage().contains("4 accounts resolve to NO role at all"),
          event.getFormattedMessage());
      assertTrue(
          event.getFormattedMessage().contains("0 accounts changed roles"),
          "nothing changed on this run — that is the whole point of the case");
    }

    /**
     * Runs one Admin-API sync of an account that holds {@code Officer} locally while Keycloak's
     * {@code Officer} no longer resolves — the shape a renamed realm role produces.
     */
    private void syncDemotedAccount() {
      UUID id = UUID.randomUUID();
      User existing = newUser(id, "demoted-callsign");
      existing.setInKeycloak(true);
      existing.setApprovalStatus(ApprovalStatus.ACTIVE);
      existing.setRoles(new HashSet<>(Set.of(codeRole("OFFICER", "Officer"))));
      when(userRepository.findById(id)).thenReturn(Optional.of(existing));

      userReconciliationService.syncUser(
          new KeycloakUserDto(id, "demoted-callsign", null, true, Set.of("Officer"), null));
    }

    /**
     * The single log event the appender captured.
     *
     * @return that event
     */
    private ILoggingEvent onlyEvent() {
      assertEquals(1, appender.list.size());
      return appender.list.getFirst();
    }

    /**
     * The most recent log event the appender captured.
     *
     * @return that event
     */
    private ILoggingEvent lastEvent() {
      assertFalse(appender.list.isEmpty());
      return appender.list.getLast();
    }
  }

  @Nested
  class SyncInputCatalogTests {

    @Test
    void getMappableRoleNames_returnsTheLocalRoleCatalogNames() {
      when(roleRepository.findAllNames()).thenReturn(Set.of("ADMIN", "OFFICER", "Bereichsleitung"));

      assertEquals(
          Set.of("ADMIN", "OFFICER", "Bereichsleitung"),
          userReconciliationService.getMappableRoleNames());
      verify(roleRepository).findAllNames();
    }

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
}
