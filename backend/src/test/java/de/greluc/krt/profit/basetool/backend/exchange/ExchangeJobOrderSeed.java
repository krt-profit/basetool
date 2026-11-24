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

package de.greluc.krt.profit.basetool.backend.exchange;

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Committed rows for the job-order exchange integration tests: units, members, seats, materials,
 * orders, assignees and stock, each statement committed on its own. {@link #close()} removes
 * everything it made, every order of its units included, whichever path wrote it.
 */
public class ExchangeJobOrderSeed {

  /** The rows of an order that do not go with it, in the order they are removed. */
  private static final List<String> ORDER_ROWS =
      List.of(
          "DELETE FROM inventory_item_job_order_allocation WHERE job_order_id = ?",
          "DELETE FROM job_order_money_correction_entry WHERE order_id = ?",
          "DELETE FROM job_order_money_commitment_change WHERE order_id = ?",
          "DELETE FROM job_order_money_operation WHERE order_id = ?",
          "DELETE FROM job_order_money_holder WHERE order_id = ?",
          "DELETE FROM notification WHERE entity_id = ?",
          "DELETE FROM audit_event WHERE subject_id = ?",
          "DELETE FROM job_order WHERE id = ?");

  /** The rows of a member that do not go with the account, in the order they are removed. */
  private static final List<String> USER_ROWS =
      List.of(
          "DELETE FROM exchange_name_withholding WHERE user_id = ?",
          "DELETE FROM notification WHERE recipient_user_id = ?",
          "DELETE FROM org_unit_membership WHERE user_id = ?",
          "DELETE FROM user_roles WHERE user_id = ?",
          "DELETE FROM app_user WHERE id = ?");

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final Set<UUID> orders = new LinkedHashSet<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();

  /** The Profit area, shared with every other test and never removed. */
  @Getter private final UUID profit;

  /**
   * Takes the beans the rows are written through.
   *
   * @param context the application context the beans come from
   */
  public ExchangeJobOrderSeed(@NotNull ApplicationContext context) {
    jdbc = context.getBean(JdbcTemplate.class);
    transactions = context.getBean(TransactionTemplate.class);
    userRepository = context.getBean(UserRepository.class);
    roleRepository = context.getBean(RoleRepository.class);
    profit = persistedProfitArea(context.getBean(OrgUnitRepository.class)).getId();
  }

  /**
   * Removes the orders of the seeded units and every other order it was told about with their rows,
   * then the stock, materials, members and units it made.
   */
  public void close() {
    if (!orgUnits.isEmpty()) {
      orders.addAll(
          jdbc.queryForList(
              "SELECT id FROM job_order WHERE requesting_org_unit_id = ANY (?)"
                  + " OR responsible_org_unit_id = ANY (?)",
              UUID.class,
              orgUnits.toArray(UUID[]::new),
              orgUnits.toArray(UUID[]::new)));
    }
    orders.forEach(id -> ORDER_ROWS.forEach(sql -> jdbc.update(sql, id)));
    materials.forEach(id -> jdbc.update("DELETE FROM inventory_item WHERE material_id = ?", id));
    locations.forEach(id -> TestPlaces.deleteLocation(jdbc, id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    users.forEach(id -> USER_ROWS.forEach(sql -> jdbc.update(sql, id)));
    orgUnits.reversed().forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
  }

  /**
   * Has {@link #close()} remove an order that may lie outside the seeded units.
   *
   * @param order the order
   */
  protected void track(@NotNull UUID order) {
    orders.add(order);
  }

  /**
   * Creates an active account holding {@code KRT Member}.
   *
   * @param username the name's prefix
   * @param admin whether the account also holds the administrator role
   * @return the account's id
   */
  @NotNull
  public UUID user(@NotNull String username, boolean admin) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    Set<Role> roles = new HashSet<>();
    roles.add(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow());
    if (admin) {
      roles.add(roleRepository.findByCode(Roles.ADMIN).orElseThrow());
    }
    user.setRoles(roles);
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
  }

  /**
   * Seats a member in a unit.
   *
   * @param member the member
   * @param unit the unit
   * @param role the membership role
   * @param logisticianFlag the Logistician flag
   */
  public void seat(
      @NotNull UUID member, @NotNull UUID unit, @NotNull String role, boolean logisticianFlag) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, ?, ?)",
        member,
        unit,
        role,
        logisticianFlag);
  }

  /**
   * Creates an active squadron.
   *
   * @param name the name's prefix
   * @param parent the parent unit, or {@code null} for none
   * @return the unit's id
   */
  @NotNull
  public UUID squadron(@NotNull String name, @Nullable UUID parent) {
    return orgUnit("SQUADRON", name, parent);
  }

  /**
   * Creates an active unit named by its prefix and its id.
   *
   * @param kind the unit kind
   * @param name the name's prefix
   * @param parent the parent unit, or {@code null} for none
   * @return the unit's id
   */
  @NotNull
  public UUID orgUnit(@NotNull String kind, @NotNull String name, @Nullable UUID parent) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, parent_org_unit_id)"
            + " VALUES (?, ?, ?, ?, TRUE, ?)",
        id,
        kind,
        name + " " + id,
        "W" + id.toString().substring(0, 7),
        parent);
    orgUnits.add(id);
    return id;
  }

  /**
   * Creates a visible refined material counted in SCU.
   *
   * @param name the name's prefix
   * @return the material's id
   */
  @NotNull
  public UUID material(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,"
            + " is_job_order, is_visible, source_systems)"
            + " VALUES (?, ?, 'REFINED', 'SCU', false, false, true, 'UEX_ONLY')",
        id,
        name + " " + id);
    materials.add(id);
    return id;
  }

  /**
   * Starts an open order; {@link OrderSeed#create()} writes it.
   *
   * @return the order's builder
   */
  @NotNull
  public OrderSeed order() {
    return new OrderSeed();
  }

  /**
   * Adds an assignee entry to an order.
   *
   * @param order the order
   * @param user the assignee
   * @param note the assignee's own note, or {@code null}
   */
  public void assignee(@NotNull UUID order, @NotNull UUID user, @Nullable String note) {
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id, note, version)"
            + " VALUES (?, ?, ?, ?, 0)",
        UUID.randomUUID(),
        order,
        user,
        note);
  }

  /**
   * Inserts 5 SCU of a seeded material at quality 700 on a location of its own.
   *
   * @param owner the owning unit
   * @param holder the member the row is recorded for
   * @param material the material
   * @return the stock row's id
   */
  @NotNull
  public UUID stockRow(@NotNull UUID owner, @NotNull UUID holder, @NotNull UUID material) {
    UUID location = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, location, "Seeded stock " + location);
    locations.add(location);
    UUID row = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,"
            + " personal, stolen, owning_org_unit_id)"
            + " VALUES (?, ?, ?, ?, 700, 5, false, false, ?)",
        row,
        holder,
        material,
        location,
        owner);
    return row;
  }

  /**
   * One material line of a seeded order.
   *
   * @param material the material
   * @param tier the quality tier's code
   * @param amount the requested amount
   */
  private record Line(@NotNull UUID material, @NotNull String tier, double amount) {}

  /**
   * Fluent builder of an open order between two seeded units, written in one transaction; every
   * setter overrides one default.
   */
  @Setter
  @Accessors(fluent = true, chain = true)
  public final class OrderSeed {

    /** The requesting unit; required. */
    private UUID requesting;

    /** The responsible unit; required. */
    private UUID responsible;

    /** The order kind, {@code MATERIAL} by default. */
    private String type = "MATERIAL";

    /** The contact handle, {@code "Seeded contact"} by default. */
    private String handle = "Seeded contact";

    /** The comment, none by default. */
    private String comment;

    /** The queue priority, {@code 1} by default. */
    private Integer priority = 1;

    @Setter(AccessLevel.NONE)
    private final List<Line> lines = new ArrayList<>();

    @Setter(AccessLevel.NONE)
    private Long target;

    private OrderSeed() {}

    /**
     * Adds a material line.
     *
     * @param material the material
     * @param tier the quality tier's code
     * @param amount the requested amount
     * @return this builder
     */
    @NotNull
    public OrderSeed material(@NotNull UUID material, @NotNull String tier, double amount) {
      lines.add(new Line(material, tier, amount));
      return this;
    }

    /**
     * Makes the order an aUEC order with a target.
     *
     * @param amount the target in whole aUEC
     * @return this builder
     */
    @NotNull
    public OrderSeed auec(long amount) {
      type = "AUEC";
      target = amount;
      return this;
    }

    /**
     * Writes the order, its lines and its target.
     *
     * @return the new order's id
     */
    @NotNull
    public UUID create() {
      UUID id = UUID.randomUUID();
      transactions.executeWithoutResult(_ -> {
        jdbc.update(
            "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
                + " requesting_org_unit_id, responsible_org_unit_id, type, handle, comment)"
                + " VALUES (?, ?, now(), now(), 0, 'OPEN', ?, ?, ?, ?, ?)",
            id,
            priority,
            Objects.requireNonNull(requesting, "requesting"),
            Objects.requireNonNull(responsible, "responsible"),
            type,
            handle,
            comment);
        lines.forEach(line ->
            jdbc.update(
                "INSERT INTO job_order_material (id, job_order_id, material_id, quality_tier_id,"
                    + " amount, created_at, updated_at, version)"
                    + " SELECT gen_random_uuid(), ?, ?, id, ?, now(), now(), 0"
                    + " FROM quality_tier WHERE code = ?",
                id,
                line.material(),
                line.amount(),
                line.tier()));
        if (target != null) {
          jdbc.update(
              "INSERT INTO job_order_money_target (order_id, amount, version) VALUES (?, ?, 0)",
              id,
              target);
        }
      });
      orders.add(id);
      return id;
    }
  }
}
