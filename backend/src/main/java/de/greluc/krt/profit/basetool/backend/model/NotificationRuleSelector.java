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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One recipient selector of a {@link NotificationRule}. Which columns are meaningful depends on
 * {@link #kind}: {@code SPECIFIC_USER} reads {@link #userId}; {@code ROLE} reads {@link #roleCode};
 * {@code ORG_RELATIVE_ROLE} reads {@link #orgRelativeRole} + {@link #contextRole}. The unused
 * columns stay {@code null}.
 */
@Entity
@Table(name = "notification_rule_selector")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class NotificationRuleSelector extends AbstractEntity<UUID> {

  /** Primary key; database-generated UUID. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning rule; the selector is deleted with its rule (cascade + FK on delete cascade). */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "rule_id", nullable = false)
  @ToString.Exclude
  private NotificationRule rule;

  /** How this selector resolves its recipients. */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private SelectorKind kind;

  /** Target user {@code sub} for a {@code SPECIFIC_USER} selector; {@code null} otherwise. */
  @Column(name = "user_id")
  private UUID userId;

  /** Stable role code for a {@code ROLE} selector; {@code null} otherwise. */
  @Column(name = "role_code", length = 64)
  private String roleCode;

  /** Org-relative role for an {@code ORG_RELATIVE_ROLE} selector; {@code null} otherwise. */
  @Enumerated(EnumType.STRING)
  @Column(name = "org_relative_role", length = 32)
  private OrgRelativeRole orgRelativeRole;

  /** Which event-carried org unit an {@code ORG_RELATIVE_ROLE} selector resolves against. */
  @Enumerated(EnumType.STRING)
  @Column(name = "context_role", length = 32)
  private NotificationContextRole contextRole;
}
