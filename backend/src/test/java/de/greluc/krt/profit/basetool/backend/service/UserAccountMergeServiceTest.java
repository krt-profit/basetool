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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Real-Postgres tests for the admin account merge (REQ-SEC-045), catching unique-constraint and
 * column-type errors in its set-based native SQL that a mocked repository would hide.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserAccountMergeServiceTest {

  @MockitoBean private JwtDecoder jwtDecoder;

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private UserAccountMergeService mergeService;

  @Autowired private UserRepository userRepository;

  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
  }

  /**
   * Creates a bare account and returns its id.
   *
   * @param callsign the username to give it
   * @return the new account's id
   */
  private UUID account(String callsign) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(callsign);
    return userRepository.saveAndFlush(user).getId();
  }

  /**
   * The headline guarantee: what a member owns lands on the surviving account.
   *
   * <p>Uses personal inventory as the representative moved table — it is the plainest case (no
   * unique constraint, no association) and the one a member notices first when it is missing.
   */
  @Test
  @DisplayName("what the source account owns moves onto the target")
  void merge_movesOwnedRowsOntoTheTarget() {
    UUID source = account("merge-src-" + UUID.randomUUID());
    UUID target = account("merge-tgt-" + UUID.randomUUID());
    UUID admin = account("merge-admin-" + UUID.randomUUID());
    insertPersonalItem(source, "Medkit");

    mergeService.merge(source, target, admin);

    assertThat(personalItemOwners("Medkit")).containsExactly(target);
  }

  /**
   * A handle only the source carries moves onto the survivor and leaves the source (REQ-SEC-072).
   */
  @Test
  @DisplayName("the source's RSI handle moves onto a target that has none")
  void merge_carriesTheRsiHandleOntoATargetWithoutOne() {
    UUID source = account("merge-src-" + UUID.randomUUID());
    UUID target = account("merge-tgt-" + UUID.randomUUID());
    UUID admin = account("merge-admin-" + UUID.randomUUID());
    String handle = rsiHandle();
    setRsiHandle(source, handle);

    mergeService.merge(source, target, admin);

    assertThat(userRepository.findById(target).orElseThrow().getRsiHandle()).isEqualTo(handle);
    assertThat(userRepository.findById(source).orElseThrow().getRsiHandle()).isNull();
  }

  /** When both accounts carry a handle, the survivor's wins and the source's is dropped. */
  @Test
  @DisplayName("the target's RSI handle wins and the source's is dropped")
  void merge_keepsTheTargetsRsiHandleAndDropsTheSources() {
    UUID source = account("merge-src-" + UUID.randomUUID());
    UUID target = account("merge-tgt-" + UUID.randomUUID());
    UUID admin = account("merge-admin-" + UUID.randomUUID());
    String sourceHandle = rsiHandle();
    String targetHandle = rsiHandle();
    setRsiHandle(source, sourceHandle);
    setRsiHandle(target, targetHandle);

    mergeService.merge(source, target, admin);

    assertThat(userRepository.findById(target).orElseThrow().getRsiHandle())
        .isEqualTo(targetHandle);
    assertThat(userRepository.findById(source).orElseThrow().getRsiHandle()).isNull();
  }

  /**
   * Returns a fresh handle in the RSI shape, unique per call.
   *
   * @return a handle no other account carries
   */
  private static String rsiHandle() {
    return "Rsi_" + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * Stores an RSI handle on an account.
   *
   * @param userId the account
   * @param handle the handle to store
   */
  private void setRsiHandle(UUID userId, String handle) {
    jdbc.update("UPDATE app_user SET rsi_handle = ? WHERE id = ?", handle, userId);
  }

  /**
   * A member may legitimately own the same blueprint on both accounts, and the unique constraint
   * over {@code (owner_user_id, product_key)} would reject the move. The source's duplicate is
   * dropped rather than re-pointed — safe precisely because the two accounts are one person, so the
   * duplicate carries nothing the survivor lacks.
   */
  @Test
  @DisplayName("a row the target already has is dropped, not re-pointed into a constraint")
  void merge_dropsTheDuplicateInsteadOfViolatingTheUniqueConstraint() {
    UUID source = account("merge-src-" + UUID.randomUUID());
    UUID target = account("merge-tgt-" + UUID.randomUUID());
    UUID admin = account("merge-admin-" + UUID.randomUUID());
    String product = "arclight pistol " + UUID.randomUUID();
    insertBlueprint(source, product);
    insertBlueprint(target, product);

    mergeService.merge(source, target, admin);

    assertThat(blueprintOwners(product))
        .as("exactly one row survives, and it belongs to the target")
        .containsExactly(target);
  }

  /**
   * The counterpart: a blueprint only the source owns moves rather than being dropped. Without this
   * the deduplication above could pass by deleting everything.
   */
  @Test
  @DisplayName("a row only the source has moves rather than being dropped")
  void merge_movesTheNonDuplicate() {
    UUID source = account("merge-src-" + UUID.randomUUID());
    UUID target = account("merge-tgt-" + UUID.randomUUID());
    UUID admin = account("merge-admin-" + UUID.randomUUID());
    String product = "aurora " + UUID.randomUUID();
    insertBlueprint(source, product);

    mergeService.merge(source, target, admin);

    assertThat(blueprintOwners(product)).containsExactly(target);
  }

  /**
   * Two ledgers is an accounting decision with money in it, not a duplicate to drop, so the merge
   * refuses rather than guessing which postings belong to which holder.
   */
  @Test
  @DisplayName("two bank ledgers refuse the merge rather than being silently combined")
  void merge_refusesWhenBothAccountsHoldALedger() {
    UUID source = account("merge-src-" + UUID.randomUUID());
    UUID target = account("merge-tgt-" + UUID.randomUUID());
    UUID admin = account("merge-admin-" + UUID.randomUUID());
    insertBankHolder(source);
    insertBankHolder(target);

    assertThatThrownBy(() -> mergeService.merge(source, target, admin))
        .isInstanceOf(BusinessConflictException.class)
        .hasMessageContaining("bank ledger");
  }

  /** Merging an account into itself is a mistake, not a no-op. */
  @Test
  @DisplayName("an account cannot be merged into itself")
  void merge_refusesTheSameAccountTwice() {
    UUID account = account("merge-self-" + UUID.randomUUID());

    assertThatThrownBy(() -> mergeService.merge(account, account, account))
        .isInstanceOf(BusinessConflictException.class);
  }

  /**
   * Verifies that a row recording an act, here the approval history, stays on the source account
   * instead of moving with the merge.
   */
  @Test
  @DisplayName("a row recording an act stays on the source account")
  void merge_leavesTheApprovalHistoryWhereItHappened() {
    UUID source = account("merge-src-" + UUID.randomUUID());
    UUID target = account("merge-tgt-" + UUID.randomUUID());
    UUID admin = account("merge-admin-" + UUID.randomUUID());
    jdbc.update(
        "INSERT INTO user_approval_event (id, version, created_at, updated_at, user_id,"
            + " decision, decided_by_id) VALUES (?, 0, now(), now(), ?, 'APPROVED', ?)",
        UUID.randomUUID(),
        source,
        admin);

    mergeService.merge(source, target, admin);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_approval_event WHERE user_id = ?",
                Integer.class,
                source))
        .as("the approval history of an account is not a belonging that follows a member")
        .isEqualTo(1);
  }

  private void insertPersonalItem(UUID owner, String name) {
    jdbc.update(
        """
        INSERT INTO personal_inventory_item
          (id, version, created_at, updated_at, owner_user_id, name, location_uex_id,
           location_type, location_name_snapshot, quantity)
        VALUES (?, 0, now(), now(), ?, ?, 1, 'CITY', 'Area18', 1)
        """,
        UUID.randomUUID(),
        owner,
        name);
  }

  private List<UUID> personalItemOwners(String name) {
    return jdbc.queryForList(
        "SELECT owner_user_id FROM personal_inventory_item WHERE name = ?", UUID.class, name);
  }

  private void insertBlueprint(UUID owner, String productKey) {
    jdbc.update(
        """
        INSERT INTO personal_blueprint
          (id, version, created_at, updated_at, owner_user_id, product_key, product_name)
        VALUES (?, 0, now(), now(), ?, ?, ?)
        """,
        UUID.randomUUID(),
        owner,
        productKey,
        productKey);
  }

  private List<UUID> blueprintOwners(String productKey) {
    return jdbc.queryForList(
        "SELECT owner_user_id FROM personal_blueprint WHERE product_key = ?",
        UUID.class,
        productKey);
  }

  private void insertBankHolder(UUID user) {
    jdbc.update(
        "INSERT INTO bank_holder (id, version, created_at, updated_at, user_id, handle)"
            + " VALUES (?, 0, now(), now(), ?, ?)",
        UUID.randomUUID(),
        user,
        "holder-" + user);
  }
}
