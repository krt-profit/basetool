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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single notification addressed to exactly one recipient.
 *
 * <p>Notifications form a per-user inbox isolated by {@link #recipientUserId} (the Keycloak {@code
 * sub}, which equals {@code app_user.id}); they are deliberately <b>not</b> org-unit scoped, so
 * this entity carries no owning org unit and is excluded from the staffel-scoped service whitelist
 * (REQ-NOTIF-004, mirrors the bank per-grant model). The {@link #type} plus the JSON {@link
 * #params} let the frontend render localized text without the backend ever storing a language
 * string, and the loose {@link #entityType}/{@link #entityId} pair deep-links back to the
 * originating aggregate while surviving its deletion.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification extends AbstractEntity<UUID> {

  /** Primary key; database-generated UUID. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Keycloak {@code sub} of the sole recipient; every inbox query filters on this column. */
  @Column(name = "recipient_user_id", nullable = false)
  private UUID recipientUserId;

  /**
   * Machine type of the notification, rendered by the frontend via {@code notifications.type.*}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 64)
  private NotificationType type;

  /**
   * JSON object of i18n render parameters as plain text, or {@code null} when the type needs none.
   * Stored opaque; never queried into.
   */
  @Column(name = "params", columnDefinition = "TEXT")
  private String params;

  /**
   * Loose type tag of the originating aggregate (e.g. {@code JOB_ORDER}); {@code null} if absent.
   */
  @Column(name = "entity_type", length = 64)
  private String entityType;

  /** Loose id of the originating aggregate for deep-linking; {@code null} if absent. No FK. */
  @Column(name = "entity_id")
  private UUID entityId;

  /** Whether the recipient has marked this notification read. */
  @Column(name = "is_read", nullable = false)
  @Builder.Default
  private boolean read = false;

  /** When the notification was marked read, or {@code null} while unread. */
  @Column(name = "read_at")
  private Instant readAt;
}
