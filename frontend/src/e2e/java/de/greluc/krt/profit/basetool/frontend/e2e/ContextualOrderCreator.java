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

package de.greluc.krt.profit.basetool.frontend.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Seeds an order through a temporary contextual writer and restores its original memberships. */
@RequiredArgsConstructor
final class ContextualOrderCreator {
  /** The authenticated backend fixture transport. */
  private final @NotNull BackendSeeder seeder;

  /**
   * Creates an order while preserving every original Staffel membership and role flag.
   *
   * @param admin the fixture administrator
   * @param adminPassword the administrator's test password
   * @param creator the user who creates the order
   * @param creatorPassword the creator's test password
   * @param unit the responsible and requesting Staffel
   * @param handle the order contact
   * @param material the requested material
   * @param quality the minimum material quality
   * @param amount the requested quantity
   * @return the created order identity
   */
  String create(
      String admin,
      String adminPassword,
      String creator,
      String creatorPassword,
      @NotNull String unit,
      String handle,
      String material,
      int quality,
      double amount) {
    try (var _ = grantWriter(admin, adminPassword, creator, creatorPassword, unit)) {
      return seeder.createJobOrder(
          creator, creatorPassword, unit, handle, material, quality, amount);
    }
  }

  /**
   * Grants contextual order authority until the returned membership restorer closes.
   *
   * @param admin the fixture administrator
   * @param adminPassword the administrator's test password
   * @param writer the user receiving the Staffel write authority
   * @param writerPassword the writer's test password
   * @param unit the Staffel receiving the membership
   * @return the original membership snapshot
   */
  RestoreStaffeln grantWriter(
      String admin, String adminPassword, String writer, String writerPassword, String unit) {
    String userId = seeder.getUserId(writer, writerPassword);
    String membershipPath = "/api/v1/org-units/members/" + userId + "/memberships";
    JsonArray memberships =
        JsonParser.parseString(seeder.getBody(admin, adminPassword, membershipPath + "/detail"))
            .getAsJsonObject()
            .getAsJsonArray("memberships");
    JsonArray original = new JsonArray();
    for (JsonElement element : memberships) {
      JsonObject membership = element.getAsJsonObject();
      if ("SQUADRON".equals(membership.get("kind").getAsString())) {
        JsonObject staffel = new JsonObject();
        staffel.addProperty("squadronId", membership.get("orgUnitId").getAsString());
        staffel.addProperty("isLogistician", enabled(membership.get("isLogistician")));
        staffel.addProperty("isMissionManager", enabled(membership.get("isMissionManager")));
        original.add(staffel);
      }
    }
    JsonArray granted = original.deepCopy();
    JsonObject target = null;
    for (JsonElement element : granted) {
      JsonObject staffel = element.getAsJsonObject();
      if (unit.equals(staffel.get("squadronId").getAsString())) {
        target = staffel;
      }
    }
    if (target == null) {
      target = new JsonObject();
      target.addProperty("squadronId", unit);
      target.addProperty("isMissionManager", false);
      granted.add(target);
    }
    target.addProperty("isLogistician", true);
    replaceStaffeln(seeder, admin, adminPassword, membershipPath, granted);
    return new RestoreStaffeln(seeder, admin, adminPassword, membershipPath, original);
  }

  private static boolean enabled(@Nullable JsonElement value) {
    return value != null && !value.isJsonNull() && value.getAsBoolean();
  }

  private static void replaceStaffeln(
      @NotNull BackendSeeder seeder,
      String admin,
      String password,
      String path,
      JsonArray staffeln) {
    JsonObject request = new JsonObject();
    request.add("staffeln", staffeln);
    int status = seeder.patchForStatus(admin, password, path, request.toString());
    if (status < 200 || status >= 300) {
      throw new IllegalStateException("Fixture membership PATCH failed: HTTP " + status);
    }
  }

  /**
   * Restores the fixture user's original Staffel memberships when closed.
   *
   * @param seeder the backend fixture transport
   * @param admin the fixture administrator
   * @param password the administrator's test password
   * @param path the membership endpoint
   * @param staffeln the original Staffel memberships
   */
  record RestoreStaffeln(
      BackendSeeder seeder, String admin, String password, String path, JsonArray staffeln)
      implements AutoCloseable {
    /** Restores the original Staffel memberships through the backend API. */
    @Override
    public void close() {
      replaceStaffeln(seeder, admin, password, path, staffeln);
    }
  }
}
