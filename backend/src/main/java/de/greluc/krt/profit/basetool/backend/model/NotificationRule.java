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

package de.greluc.krt.profit.basetool.backend.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * An admin-managed rule that maps an {@link #eventType} to the recipients resolved by {@link
 * #selectors}, who receive a {@link #notificationType} notification when that event fires
 * (REQ-NOTIF-007).
 *
 * <p>{@link #excludeActor} drops the triggering user from the recipients.
 */
@Entity
@Table(name = "notification_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRule extends AbstractEntity<UUID> {

  /** Primary key; database-generated UUID. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The trigger this rule matches. */
  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 64)
  private NotificationEventType eventType;

  /** The type of notification produced for each resolved recipient. */
  @Enumerated(EnumType.STRING)
  @Column(name = "notification_type", nullable = false, length = 64)
  private NotificationType notificationType;

  /** Free-text admin description of the rule's intent; {@code null} allowed. */
  @Column(name = "description", length = 255)
  private String description;

  /** Whether the rule is active; disabled rules are ignored by the engine. */
  @Column(name = "enabled", nullable = false)
  @Builder.Default
  private boolean enabled = true;

  /** Whether the user who triggered the event is removed from the resolved recipients. */
  @Column(name = "exclude_actor", nullable = false)
  @Builder.Default
  private boolean excludeActor = true;

  /** Recipient selectors; the rule owns them (cascade + orphan removal). */
  @OneToMany(
      mappedBy = "rule",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  @Builder.Default
  private Set<NotificationRuleSelector> selectors = new HashSet<>();

  /**
   * Returns an unmodifiable view of the recipient selectors. Callers mutate the collection only
   * through {@link #addSelector(NotificationRuleSelector)} / {@link #clearSelectors()} so the
   * owning set (and its orphan-removal bookkeeping) stays encapsulated.
   *
   * @return an unmodifiable view of the selectors
   */
  @NotNull
  @UnmodifiableView
  public Set<NotificationRuleSelector> getSelectors() {
    return Collections.unmodifiableSet(selectors);
  }

  /**
   * Attaches a selector to this rule, wiring the back-reference so the cascade persists it.
   *
   * @param selector the selector to add
   */
  public void addSelector(@NotNull NotificationRuleSelector selector) {
    selector.setRule(this);
    selectors.add(selector);
  }

  /**
   * Removes all selectors in place, triggering orphan removal on flush. Used by the update path
   * before the new selectors are re-added.
   */
  public void clearSelectors() {
    selectors.clear();
  }
}
