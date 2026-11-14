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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.internal.HangarService;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemService;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.patchchange.api.RelocationPendingException;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Runs a home-location phase against PostgreSQL (REQ-PATCH-021…027): who is asked, the choice and
 * its once-only, in-place relocation, the lock on the web and the exchange, the end of a phase and
 * its supersession.
 *
 * <p>Its live times lie in 2000, before every other test's rows; the rows and ships it moves have
 * creation times set in 1999. Every patch change is removed afterwards, as in {@link
 * PatchChangeTriggerIntegrationTest}.
 */
@SpringBootTest
class HomeLocationRelocationIntegrationTest {

  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant LATER_LIVE_AT = Instant.parse("2000-06-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");

  @Autowired private PatchChangeTrigger trigger;
  @Autowired private PatchChangeHistory history;
  @Autowired private HomeLocationPhases phases;
  @Autowired private HomeLocationPrompts prompts;
  @Autowired private MemberRelocations relocations;
  @Autowired private HomeLocationPhaseEnd phaseEnd;
  @Autowired private InventoryItemService inventory;
  @Autowired private HangarService hangar;
  @Autowired private UserRepository userRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> locations = new ArrayList<>();
  private UUID admin;
  private UUID member;
  private UUID newcomer;
  private UUID logistician;
  private UUID home;
  private UUID port;
  private UUID station;
  private UUID material;
  private UUID squadron;
  private UUID shipType;

  @BeforeEach
  void setUp() {
    admin = persistUser("reloc-admin-", null);
    member = persistUser("reloc-member-", BEFORE);
    newcomer = persistUser("reloc-newcomer-", null);
    logistician = persistUser("reloc-logistician-", null);
    home = location("Reloc home ", true);
    port = location("Reloc port ", false);
    station = location("Reloc station ", false);
    material = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        "Reloc ore " + material);
    squadron = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_profit_eligible,"
            + " parent_org_unit_id) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE, NULL)",
        squadron,
        "Reloc squadron " + squadron,
        "R" + squadron.toString().substring(0, 8));
    ShipType type = new ShipType();
    type.setName("Reloc hull " + UUID.randomUUID());
    shipType = shipTypeRepository.saveAndFlush(type).getId();
    authenticate(admin, true);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    jdbc.update("DELETE FROM patch_change");
    List<UUID> users = List.of(admin, member, newcomer, logistician);
    for (UUID user : users) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", user);
      jdbc.update("DELETE FROM ship WHERE owner_id = ?", user);
    }
    for (UUID user : users) {
      jdbc.update("DELETE FROM app_user WHERE id = ?", user);
    }
    jdbc.update("DELETE FROM ship_type WHERE id = ?", shipType);
    jdbc.update("DELETE FROM org_unit WHERE id = ?", squadron);
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    for (UUID location : locations) {
      UUID city =
          jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, location);
      jdbc.update("DELETE FROM location WHERE id = ?", location);
      jdbc.update("DELETE FROM city WHERE id = ?", city);
    }
  }

  @Test
  void theChoiceMovesWhatTheMemberHeldBeforeTheLiveTimeOnceAndInPlace() {
    UUID own = row(port, false, null, BEFORE);
    UUID pooled = row(port, false, squadron, BEFORE);
    UUID elsewhere = row(station, true, null, BEFORE);
    UUID atHome = row(home, true, null, BEFORE);
    UUID later = row(port, true, null, null);
    jdbc.update("UPDATE inventory_item SET created_at = now() WHERE id = ?", later);
    UUID shipAtPort = ship(port, BEFORE);
    UUID shipWithoutPlace = ship(null, BEFORE);
    UUID laterShip = ship(station, Instant.now());
    PatchChangeDto recorded = trigger("0.1", LIVE_AT);

    assertThat(phases.mustChoose(member)).isTrue();
    HomeLocationPromptDto prompt = prompts.promptFor(member);
    assertThat(prompt.state()).isEqualTo(MemberRelocationState.PENDING);
    assertThat(prompt.locations()).extracting(HomeLocationOptionDto::id).contains(home);
    assertThat(prompt.preview())
        .filteredOn(entry -> port.equals(entry.locationId()))
        .singleElement()
        .satisfies(entry -> {
          assertThat(entry.rows()).isEqualTo(2);
          assertThat(entry.ships()).isEqualTo(1);
          assertThat(entry.pools())
              .extracting(HomeLocationPoolRowsDto::orgUnitId)
              .containsExactly(squadron);
        });
    assertThat(prompt.preview())
        .filteredOn(entry -> entry.locationId() == null)
        .singleElement()
        .extracting(HomeLocationPreviewDto::ships)
        .isEqualTo(1L);

    long pendingBefore = phases.countPending();
    authenticate(member, false);
    RelocationResultDto result = relocations.relocate(member, home, RelocationChooser.SELF);

    assertThat(pendingBefore).isPositive();
    assertThat(phases.countPending()).isEqualTo(pendingBefore - 1);

    assertThat(result.rows()).isEqualTo(3);
    assertThat(result.ships()).isEqualTo(2);
    assertThat(locationOf("inventory_item", own)).isEqualTo(home);
    assertThat(locationOf("inventory_item", pooled)).isEqualTo(home);
    assertThat(locationOf("inventory_item", elsewhere)).isEqualTo(home);
    assertThat(locationOf("inventory_item", later)).isEqualTo(port);
    assertThat(versionOf("inventory_item", own)).isEqualTo(1L);
    assertThat(versionOf("inventory_item", atHome)).isZero();
    assertThat(locationOf("ship", shipAtPort)).isEqualTo(home);
    assertThat(locationOf("ship", shipWithoutPlace)).isEqualTo(home);
    assertThat(locationOf("ship", laterShip)).isEqualTo(station);
    assertThat(versionOf("ship", shipAtPort)).isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT chosen_by FROM patch_change_relocation WHERE user_id = ?",
                String.class,
                member))
        .isEqualTo("SELF");
    assertThat(auditCount("PATCH_CHANGE_RELOCATED", member)).isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_change WHERE user_id = ?"
                    + " AND source_channel = 'patch' AND source_patch_change = ?",
                Long.class,
                member,
                recorded.id()))
        .isPositive();
    assertThat(phases.mustChoose(member)).isFalse();
    HomeLocationPromptDto done = prompts.promptFor(member);
    assertThat(done.state()).isEqualTo(MemberRelocationState.RELOCATED);
    assertThat(done.chosen()).isNotNull().extracting(HomeLocationOptionDto::id).isEqualTo(home);

    assertThatThrownBy(() -> relocations.relocate(member, home, RelocationChooser.SELF))
        .isInstanceOf(BusinessConflictException.class)
        .hasMessage("error.patch_change.already_relocated");
    assertThat(versionOf("inventory_item", own)).isEqualTo(1L);
  }

  @Test
  void anAccountYoungerThanTheLiveTimeIsNotAskedAndCannotChoose() {
    trigger("0.1", LIVE_AT);

    assertThat(phases.mustChoose(newcomer)).isFalse();
    assertThat(prompts.promptFor(newcomer).state()).isEqualTo(MemberRelocationState.NOT_ASKED);
    assertThatThrownBy(() -> relocations.relocate(newcomer, home, RelocationChooser.SELF))
        .isInstanceOf(BusinessConflictException.class);
  }

  @Test
  void onlyACuratedHomeLocationCanBeChosen() {
    trigger("0.1", LIVE_AT);

    assertThatThrownBy(() -> relocations.relocate(member, port, RelocationChooser.SELF))
        .hasMessageContaining("home_location_not_offered");
    assertThat(phases.mustChoose(member)).isTrue();
  }

  @Test
  void theLockRefusesOthersAndTheExchangeButNeitherTheHolderNorAnAdmin() {
    UUID locked = row(port, true, null, BEFORE);
    UUID later = row(port, true, null, null);
    jdbc.update("UPDATE inventory_item SET created_at = now() WHERE id = ?", later);
    UUID lockedShip = ship(port, BEFORE);
    trigger("0.1", LIVE_AT);

    authenticate(logistician, false);
    assertThatThrownBy(() -> note(locked, logistician, true))
        .isInstanceOf(RelocationPendingException.class);
    note(later, logistician, true);

    SecurityContextHolder.getContext().setAuthentication(new Relayed(member));
    assertThatThrownBy(() -> note(locked, member, false))
        .isInstanceOf(RelocationPendingException.class);
    assertThatThrownBy(() -> hangar.deleteShip(member, lockedShip))
        .isInstanceOf(RelocationPendingException.class);

    authenticate(member, false);
    note(locked, member, false);
    authenticate(admin, true);
    note(locked, admin, true);

    relocations.relocate(member, home, RelocationChooser.ADMIN);
    authenticate(logistician, false);
    note(locked, logistician, true);
    assertThat(
            jdbc.queryForObject(
                "SELECT chosen_by FROM patch_change_relocation WHERE user_id = ?",
                String.class,
                member))
        .isEqualTo("ADMIN");
  }

  @Test
  void anAdminEndsThePhaseEarlyAndTheLockEndsWithIt() {
    UUID locked = row(port, true, null, BEFORE);
    PatchChangeDto recorded = trigger("0.1", LIVE_AT);

    PatchChangeDto ended = phaseEnd.end(recorded.id());

    assertThat(ended.phase()).isNotNull();
    assertThat(ended.phase().state()).isEqualTo(PatchChangePhaseState.ENDED);
    assertThat(ended.version()).isEqualTo(recorded.version());
    assertThat(phases.mustChoose(member)).isFalse();
    assertThat(phases.countPending()).isZero();
    assertThat(prompts.promptFor(member).state()).isEqualTo(MemberRelocationState.PHASE_ENDED);
    assertThat(auditCount("PATCH_CHANGE_PHASE_ENDED", recorded.id())).isEqualTo(1L);
    assertThatThrownBy(() -> phaseEnd.end(recorded.id()))
        .isInstanceOf(BusinessConflictException.class);
    authenticate(logistician, false);
    note(locked, logistician, true);
  }

  @Test
  void aLaterPatchChangeWithThePromptSupersedesTheOpenPhaseAndCarriesTheLockOver() {
    PatchChangeDto first = trigger("0.1", LIVE_AT);
    PatchChangeDto second = trigger("0.2", LATER_LIVE_AT);

    assertThat(history.history())
        .extracting(dto -> dto.phase() == null ? null : dto.phase().state())
        .containsExactly(PatchChangePhaseState.OPEN, PatchChangePhaseState.SUPERSEDED);
    assertThat(phases.open()).map(HomeLocationPhase::id).contains(second.id());
    assertThat(phases.mustChoose(member)).isTrue();
    assertThat(second.counts())
        .filteredOn(count -> count.kind() == PatchCount.MEMBERS_ASKED)
        .singleElement()
        .extracting(PatchCountDto::value)
        .satisfies(value -> assertThat(value).isPositive());
    assertThatThrownBy(() -> phaseEnd.end(first.id()))
        .isInstanceOf(BusinessConflictException.class);
  }

  /**
   * Triggers a rebuild, whose preset asks for a home location.
   *
   * @param label the label
   * @param liveAt the live time
   * @return the recorded patch change
   */
  private @NotNull PatchChangeDto trigger(@NotNull String label, @NotNull Instant liveAt) {
    authenticate(admin, true);
    return trigger.trigger(
        new TriggerPatchChangeRequest(
            label,
            PatchKind.REBUILD,
            liveAt,
            PatchChangeOptions.presetFor(PatchKind.REBUILD),
            label));
  }

  /**
   * Edits a row's note on the web path, which the lock guards like every row write.
   *
   * @param row the row
   * @param caller the calling member
   * @param logisticianOfRow whether the caller acts as the row's logistician
   */
  private void note(@NotNull UUID row, @NotNull UUID caller, boolean logisticianOfRow) {
    Long version =
        jdbc.queryForObject("SELECT version FROM inventory_item WHERE id = ?", Long.class, row);
    inventory.updateNote(
        row,
        new InventoryItemNoteUpdateRequest("Note " + UUID.randomUUID(), version),
        caller,
        logisticianOfRow);
  }

  /**
   * Persists an active member.
   *
   * @param prefix the username's prefix
   * @param createdAt the account's creation time, or {@code null} for now
   * @return the member's id
   */
  private @NotNull UUID persistUser(@NotNull String prefix, @Nullable Instant createdAt) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    UUID id = userRepository.saveAndFlush(user).getId();
    if (createdAt != null) {
      jdbc.update("UPDATE app_user SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
    }
    return id;
  }

  /**
   * Inserts a location on a city of its own.
   *
   * @param prefix the name's prefix
   * @param curatedHome whether it is a curated home location
   * @return its id
   */
  private @NotNull UUID location(@NotNull String prefix, boolean curatedHome) {
    UUID id = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, id, prefix + id);
    jdbc.update("UPDATE location SET is_home_location = ? WHERE id = ?", curatedHome, id);
    locations.add(id);
    return id;
  }

  /**
   * Inserts one of the member's stock rows.
   *
   * @param location the location
   * @param personal whether it is personal stock
   * @param pool the org unit it is held for, or {@code null}
   * @param createdAt its creation time, or {@code null}
   * @return its id
   */
  private @NotNull UUID row(
      @NotNull UUID location, boolean personal, @Nullable UUID pool, @Nullable Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, version, user_id, material_id, location_id, quality,
                                    amount, personal, stolen, owning_org_unit_id, created_at)
        VALUES (?, 0, ?, ?, ?, 500, 1, ?, false, ?, ?)
        """,
        id,
        member,
        material,
        location,
        personal,
        pool,
        createdAt == null ? null : Timestamp.from(createdAt));
    return id;
  }

  /**
   * Inserts one of the member's ships.
   *
   * @param location its location, or {@code null}
   * @param createdAt its creation time
   * @return its id
   */
  private @NotNull UUID ship(@Nullable UUID location, @NotNull Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, version, name, ship_type_id, owner_id, location_id, insurance,"
            + " created_at) VALUES (?, 0, 'Reloc', ?, ?, ?, 'LTI', ?)",
        id,
        shipType,
        member,
        location,
        Timestamp.from(createdAt));
    return id;
  }

  /**
   * Reads a row's or ship's location.
   *
   * @param table the table
   * @param id the row
   * @return its location
   */
  private @Nullable UUID locationOf(@NotNull String table, @NotNull UUID id) {
    return jdbc.queryForObject(
        "SELECT location_id FROM " + table + " WHERE id = ?", UUID.class, id);
  }

  /**
   * Reads a row's or ship's version.
   *
   * @param table the table
   * @param id the row
   * @return its version
   */
  private @Nullable Long versionOf(@NotNull String table, @NotNull UUID id) {
    return jdbc.queryForObject("SELECT version FROM " + table + " WHERE id = ?", Long.class, id);
  }

  /**
   * Counts the audit events of a type about a subject.
   *
   * @param type the event type
   * @param subject the subject
   * @return the count
   */
  private long auditCount(@NotNull String type, @NotNull UUID subject) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_event WHERE event_type = ? AND subject_id = ?",
            Long.class,
            type,
            subject);
    return count == null ? 0 : count;
  }

  /**
   * Signs a member in on the web.
   *
   * @param user the member
   * @param asAdmin whether the member is an admin
   */
  private static void authenticate(@NotNull UUID user, boolean asAdmin) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject(user.toString())
            .claim("azp", "basetool-frontend")
            .issuedAt(Instant.now())
            .build();
    Authentication auth =
        new JwtAuthenticationToken(
            jwt,
            asAdmin
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  /** An exchange client's relayed request on the member's behalf. */
  private static final class Relayed extends AbstractAuthenticationToken
      implements SubjectAuthentication {

    private final UUID member;

    /**
     * Relays for a member.
     *
     * @param member the member
     */
    private Relayed(@NotNull UUID member) {
      super(List.of());
      this.member = member;
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return member.toString();
    }

    @Override
    public @NotNull String subject() {
      return member.toString();
    }

    @Override
    public String externalClient() {
      return "versekit";
    }
  }
}
