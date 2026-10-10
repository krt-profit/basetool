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

package de.greluc.krt.profit.basetool.frontend.notification;

import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationPreferenceDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Sorts the notification types of the preferences card into the areas a member knows them by
 * (REQ-NOTIF-027). The area is read from the type name's prefix; a type no prefix claims lands in
 * {@link #OTHER}, so a new type is never lost, and a test pins that every backend type has an area
 * and a label.
 */
public final class NotificationPreferenceGroups {

  /** The area for a type no prefix claims. */
  public static final String OTHER = "other";

  private static final Map<String, String> PREFIX_TO_GROUP = prefixes();

  private NotificationPreferenceGroups() {}

  /**
   * One area of the card.
   *
   * @param key the area key, labelled by {@code profile.notifications.group.<key>}
   * @param items the area's types, in the order the backend listed them
   */
  public record Group(@NotNull String key, @NotNull List<NotificationPreferenceDto> items) {}

  private static Map<String, String> prefixes() {
    Map<String, String> prefixes = new LinkedHashMap<>();
    prefixes.put("JOB_ORDER_", "orders");
    prefixes.put("BANK_", "bank");
    prefixes.put("MATERIAL_", "market");
    prefixes.put("INVENTORY_", "inventory");
    prefixes.put("EXCHANGE_", "connectedApps");
    prefixes.put("DISCORD_", "account");
    prefixes.put("ACCOUNT_DELETION_", "account");
    return prefixes;
  }

  /**
   * The area key of a notification type.
   *
   * @param type the notification type name
   * @return the area key, {@link #OTHER} when no prefix claims the type
   */
  @NotNull
  public static String groupOf(@NotNull String type) {
    return PREFIX_TO_GROUP.entrySet().stream()
        .filter(entry -> type.startsWith(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(OTHER);
  }

  /**
   * Groups the preferences into areas, in the order the areas first appear.
   *
   * @param preferences the backend's list, one entry per type
   * @return the non-empty areas; never {@code null}
   */
  @NotNull
  public static List<Group> group(@NotNull List<NotificationPreferenceDto> preferences) {
    Map<String, List<NotificationPreferenceDto>> byGroup = new LinkedHashMap<>();
    for (NotificationPreferenceDto preference : preferences) {
      byGroup.computeIfAbsent(groupOf(preference.type()), key -> new ArrayList<>()).add(preference);
    }
    return byGroup.entrySet().stream()
        .map(entry -> new Group(entry.getKey(), List.copyOf(entry.getValue())))
        .toList();
  }
}
