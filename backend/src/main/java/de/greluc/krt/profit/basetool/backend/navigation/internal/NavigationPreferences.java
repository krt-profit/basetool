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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

/**
 * The root of one member's navigation preferences: one row per member, holding the version counter
 * of every section (ADR-0298). Each section's rows reference this root, never {@code app_user}.
 *
 * <p>A section counter is a plain business value bumped by a conditional {@code UPDATE}, so an edit
 * of one section never 409s an edit of another.
 */
@Entity
@Table(name = "navigation_preferences")
@Getter
@NoArgsConstructor
public class NavigationPreferences {

  /** {@code app_user.id} of the owner. */
  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Setter
  @Column(name = "favourites_version", nullable = false)
  private long favouritesVersion;

  @Setter
  @Column(name = "hidden_version", nullable = false)
  private long hiddenVersion;

  @Setter
  @Column(name = "order_member_version", nullable = false)
  private long orderMemberVersion;

  @Setter
  @Column(name = "order_admin_version", nullable = false)
  private long orderAdminVersion;

  /**
   * The moved group and link keys of both modes, scope by scope; the list position is the stored
   * order, and a scope's keys are contiguous per mode.
   */
  @ElementCollection(fetch = FetchType.EAGER)
  @Fetch(FetchMode.SELECT)
  @CollectionTable(name = "navigation_order", joinColumns = @JoinColumn(name = "preferences_id"))
  @OrderColumn(name = "order_index")
  private List<OrderRow> orderRows = new ArrayList<>();

  /** The hidden page keys; a page is never both hidden and a favourite. */
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "navigation_hidden", joinColumns = @JoinColumn(name = "preferences_id"))
  @Column(name = "entry_key", nullable = false, length = NavigationLimits.MAX_ENTRY_KEY_LENGTH)
  private Set<String> hidden = new LinkedHashSet<>();

  /** The favourite entry keys in display order. */
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "navigation_favourite",
      joinColumns = @JoinColumn(name = "preferences_id"))
  @OrderColumn(name = "order_index")
  @Column(name = "entry_key", nullable = false, length = NavigationLimits.MAX_ENTRY_KEY_LENGTH)
  private List<String> favourites = new ArrayList<>();

  /**
   * Creates the empty preferences of a member.
   *
   * @param userId the owner's {@code app_user.id}
   */
  public NavigationPreferences(UUID userId) {
    this.userId = userId;
  }

  /**
   * Replaces the favourites by the given keys, in order.
   *
   * @param keys the new favourite keys
   */
  public void replaceFavourites(List<String> keys) {
    favourites.clear();
    favourites.addAll(keys);
  }

  /**
   * Replaces the hidden page keys.
   *
   * @param keys the new hidden keys
   */
  public void replaceHidden(Collection<String> keys) {
    hidden.clear();
    hidden.addAll(keys);
  }

  /**
   * The stored order of a mode.
   *
   * @param mode the navigation mode
   * @return the keys by scope, scopes and keys in stored order
   */
  public Map<String, List<String>> orderOf(NavigationMode mode) {
    Map<String, List<String>> scopes = new LinkedHashMap<>();
    orderRows.stream()
        .filter(row -> row.mode().equals(mode.getTag()))
        .forEach(
            row -> scopes.computeIfAbsent(row.scope(), _ -> new ArrayList<>()).add(row.entryKey()));
    return scopes;
  }

  /**
   * The order version of a mode.
   *
   * @param mode the navigation mode
   * @return the version the next write of that mode's order echoes
   */
  public long orderVersionOf(NavigationMode mode) {
    return mode == NavigationMode.MEMBER ? orderMemberVersion : orderAdminVersion;
  }

  /**
   * Replaces the stored order of one scope of a mode; no keys removes the scope.
   *
   * @param mode the navigation mode
   * @param scope {@code groups} or a group key
   * @param keys the scope's keys in their new order
   */
  public void replaceOrderScope(NavigationMode mode, String scope, List<String> keys) {
    orderRows.removeIf(row -> row.mode().equals(mode.getTag()) && row.scope().equals(scope));
    keys.forEach(key -> orderRows.add(new OrderRow(mode.getTag(), scope, key)));
  }

  /**
   * Removes the whole stored order of a mode.
   *
   * @param mode the navigation mode
   */
  public void clearOrder(NavigationMode mode) {
    orderRows.removeIf(row -> row.mode().equals(mode.getTag()));
  }

  /**
   * Removes the given keys from every scope of every mode.
   *
   * @param keys the keys to remove
   * @return the modes in which a stored key was removed
   */
  public Set<NavigationMode> removeFromOrder(Collection<String> keys) {
    Set<NavigationMode> changed = EnumSet.noneOf(NavigationMode.class);
    for (NavigationMode mode : NavigationMode.values()) {
      if (orderRows.removeIf(
          row -> row.mode().equals(mode.getTag()) && keys.contains(row.entryKey()))) {
        changed.add(mode);
      }
    }
    return changed;
  }
}
