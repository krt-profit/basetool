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

import de.greluc.krt.profit.basetool.backend.model.BulkRebookMode;
import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies against PostgreSQL that a committed Lager transfer which changes a row's owner reaches
 * the new and the previous owner as notifications through the seeded rules, and that a transfer
 * which keeps the owner or rolls back reaches nobody (REQ-INV-055).
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class InventoryTransferNotificationIntegrationTest {

  private static final long WAIT_MILLIS = 20_000;
  private static final long POLL_MILLIS = 100;

  @Autowired private InventoryCheckoutService checkoutService;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();

  @AfterEach
  void removeFixtures() {
    for (UUID user : users) {
      jdbc.update("DELETE FROM notification WHERE recipient_user_id = ?", user);
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", user);
    }
    locations.forEach(locationRepository::deleteById);
    materials.forEach(materialRepository::deleteById);
    users.forEach(userRepository::deleteById);
  }

  @Test
  void anOwnersTransferToAnotherMemberNotifiesTheNewOwnerOnly() {
    User owner = user("Owner");
    User receiver = user("Receiver");
    Location from = location("Area18");
    Location to = location("Lorville");
    Material quantanium = material("Quantanium", QuantityType.SCU);
    InventoryItem row = row(owner, quantanium, from, 640, 10.0);

    transactionTemplate.executeWithoutResult(
        _ ->
            checkoutService.bookOutInventoryItem(
                row.getId(), transfer(4.0, receiver.getId(), to.getId()), owner.getId(), false));

    List<Map<String, Object>> received = awaitNotifications(receiver.getId(), 1);
    assertThat(received.getFirst().get("type")).isEqualTo("INVENTORY_TRANSFERRED_TO_USER");
    assertThat((String) received.getFirst().get("params"))
        .contains("\"actor\":\"Owner\"")
        .contains("\"count\":\"1\"")
        .contains("4 SCU " + quantanium.getName() + " (Q640) in " + to.getName());
    assertThat(notifications(owner.getId())).isEmpty();
  }

  @Test
  void aLogisticiansTransferOfAnotherMembersStockNotifiesBothOwners() {
    User owner = user("Owner");
    User receiver = user("Receiver");
    User logistician = user("Logistician");
    Location from = location("Area18");
    Material medpen = material("Medpen", QuantityType.PIECE);
    InventoryItem row = row(owner, medpen, from, 500, 6.0);

    transactionTemplate.executeWithoutResult(
        _ ->
            checkoutService.bookOutInventoryItem(
                row.getId(), transfer(6.0, receiver.getId(), null), logistician.getId(), true));

    List<Map<String, Object>> received = awaitNotifications(receiver.getId(), 1);
    assertThat(received.getFirst().get("type")).isEqualTo("INVENTORY_TRANSFERRED_TO_USER");
    assertThat((String) received.getFirst().get("params"))
        .contains("\"actor\":\"Logistician\"")
        .contains("6× " + medpen.getName() + " (Q500) in " + from.getName());
    List<Map<String, Object>> lost = awaitNotifications(owner.getId(), 1);
    assertThat(lost.getFirst().get("type")).isEqualTo("INVENTORY_TRANSFERRED_FROM_USER");
    assertThat((String) lost.getFirst().get("params"))
        .contains("\"actor\":\"Logistician\"")
        .contains("\"newOwner\":\"Receiver\"")
        .contains("6× " + medpen.getName() + " (Q500) in " + from.getName());
    assertThat(notifications(logistician.getId())).isEmpty();
  }

  @Test
  void aBulkTransferNotifiesTheNewOwnerOnceWithEveryMovedLot() {
    User owner = user("Owner");
    User receiver = user("Receiver");
    Location from = location("Area18");
    Material laranite = material("Laranite", QuantityType.SCU);
    Material kit = material("Repair Kit", QuantityType.PIECE);
    InventoryItem ore = row(owner, laranite, from, 800, 12.5);
    InventoryItem kits = row(owner, kit, from, 300, 3.0);

    transactionTemplate.executeWithoutResult(
        _ ->
            checkoutService.bulkRebook(
                new BulkRebookRequest(
                    List.of(ore.getId(), kits.getId()),
                    BulkRebookMode.LOCATION,
                    receiver.getId(),
                    null,
                    null,
                    null),
                owner.getId()));

    List<Map<String, Object>> received = awaitNotifications(receiver.getId(), 1);
    assertThat(received).hasSize(1);
    assertThat((String) received.getFirst().get("params"))
        .contains("\"count\":\"2\"")
        .contains("12.5 SCU " + laranite.getName() + " (Q800) in " + from.getName())
        .contains("3× " + kit.getName() + " (Q300) in " + from.getName());
    assertThat(notifications(owner.getId())).isEmpty();
  }

  @Test
  void aRolledBackOrLocationOnlyTransferNotifiesNobody() {
    User owner = user("Owner");
    User receiver = user("Receiver");
    User control = user("Control");
    Location from = location("Area18");
    Location to = location("Lorville");
    Material material = material("Agricium", QuantityType.SCU);
    InventoryItem rolledBack = row(owner, material, from, 500, 5.0);
    InventoryItem moved = row(owner, material, from, 500, 5.0);
    InventoryItem controlRow = row(owner, material, from, 500, 5.0);

    transactionTemplate.executeWithoutResult(
        status -> {
          checkoutService.bookOutInventoryItem(
              rolledBack.getId(), transfer(5.0, receiver.getId(), null), owner.getId(), false);
          status.setRollbackOnly();
        });
    transactionTemplate.executeWithoutResult(
        _ ->
            checkoutService.bookOutInventoryItem(
                moved.getId(), transfer(5.0, null, to.getId()), owner.getId(), false));
    transactionTemplate.executeWithoutResult(
        _ ->
            checkoutService.bookOutInventoryItem(
                controlRow.getId(), transfer(5.0, control.getId(), null), owner.getId(), false));

    awaitNotifications(control.getId(), 1);
    assertThat(notifications(receiver.getId())).isEmpty();
    assertThat(notifications(owner.getId())).isEmpty();
  }

  /**
   * Waits until the member has at least {@code expected} notifications.
   *
   * @param recipient the member
   * @param expected the minimum number of notifications
   * @return the member's notifications
   * @throws AssertionError when they do not arrive in time
   */
  private List<Map<String, Object>> awaitNotifications(UUID recipient, int expected) {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    List<Map<String, Object>> found = notifications(recipient);
    while (found.size() < expected && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for notifications", e);
      }
      found = notifications(recipient);
    }
    assertThat(found).as("notifications of %s", recipient).hasSizeGreaterThanOrEqualTo(expected);
    return found;
  }

  /**
   * Reads a member's notifications.
   *
   * @param recipient the member
   * @return type and params of each notification
   */
  private List<Map<String, Object>> notifications(UUID recipient) {
    return jdbc.queryForList(
        "SELECT type, params FROM notification WHERE recipient_user_id = ?", recipient);
  }

  /**
   * A transfer of part of a row.
   *
   * @param amount the amount
   * @param targetUser the new owner, or {@code null} to keep the owner
   * @param targetLocation the new location, or {@code null} to keep the location
   * @return the book-out
   */
  private static InventoryItemBookOutDto transfer(
      double amount, UUID targetUser, UUID targetLocation) {
    return new InventoryItemBookOutDto(
        amount,
        targetUser,
        targetLocation,
        CheckoutType.TRANSFER,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Persists a member with the given display name.
   *
   * @param displayName the member's display name
   * @return the member
   */
  private User user(String displayName) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    user.setDisplayName(displayName);
    users.add(user.getId());
    return userRepository.save(user);
  }

  /**
   * Persists a location with a unique name built from {@code name}.
   *
   * @param name the visible part of the name
   * @return the location
   */
  private Location location(String name) {
    Location location = new Location();
    location.setName(name + "-" + UUID.randomUUID());
    Location saved = locationRepository.save(location);
    locations.add(saved.getId());
    return saved;
  }

  /**
   * Persists a raw material with a unique name built from {@code name}.
   *
   * @param name the visible part of the name
   * @param quantityType how the material is counted
   * @return the material
   */
  private Material material(String name, QuantityType quantityType) {
    Material material = new Material();
    material.setName(name + "-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material.setQuantityType(quantityType);
    Material saved = materialRepository.save(material);
    materials.add(saved.getId());
    return saved;
  }

  /**
   * Persists a shared Lager row.
   *
   * @param owner the row's member
   * @param material the material
   * @param location the location
   * @param quality the quality, or {@code null}
   * @param amount the amount
   * @return the row
   */
  private InventoryItem row(
      User owner, Material material, Location location, Integer quality, double amount) {
    InventoryItem item = new InventoryItem();
    item.setUser(owner);
    item.setMaterial(material);
    item.setLocation(location);
    item.setQuality(quality);
    item.setAmount(amount);
    item.setPersonal(false);
    return inventoryItemRepository.save(item);
  }
}
