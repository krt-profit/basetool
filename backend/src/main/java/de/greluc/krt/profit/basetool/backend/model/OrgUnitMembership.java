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
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Membership of a {@link User} in an {@link OrgUnit}, keyed by the composite {@link
 * OrgUnitMembershipId} {@code (user_id, org_unit_id)}.
 *
 * <p>Carries the per-link state that decides what the user may do in the org unit: the capability
 * flags, the {@link #role} rank, the Kommandogruppe and {@code joined_at}. The {@link #kind} column
 * mirrors {@code org_unit.kind} via a database trigger and is read-only here.
 */
@Entity
@Table(name = "org_unit_membership")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class OrgUnitMembership {

  /**
   * Composite primary key; the {@code userId} half is derived from {@link #user} via
   * {@code @MapsId}, so callers set {@link #user} and the {@code orgUnitId} half.
   */
  @EmbeddedId private OrgUnitMembershipId id;

  /**
   * The user holding this membership. {@code @MapsId("userId")} ties the {@code user_id} column to
   * the {@link OrgUnitMembershipId#getUserId()} half of the composite key — Hibernate writes {@code
   * user_id} from {@link User#getId()} on insert and the embedded key picks it up automatically.
   * Lazy-fetched so listing memberships does not eagerly hydrate every user row.
   */
  @MapsId("userId")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @ToString.Exclude
  private User user;

  /**
   * Denormalised {@code org_unit.kind} of the referenced org unit, maintained by a database trigger
   * and read-only at the JPA layer.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32, insertable = false, updatable = false)
  private OrgUnitKind kind;

  /**
   * {@code true} when the membership grants the Logistician capability within the referenced org
   * unit.
   */
  @Column(name = "is_logistician", nullable = false)
  private boolean isLogistician = false;

  /**
   * {@code true} when the membership grants the Mission Manager capability within the referenced
   * org unit.
   */
  @Column(name = "is_mission_manager", nullable = false)
  private boolean isMissionManager = false;

  /**
   * The leadership rank of this membership and the single source of truth for the user's standing
   * in the org unit (REQ-ROLE-001). Each rank is allowed only on its matching org-unit kind;
   * defaults to {@link MembershipRole#MEMBER}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 40)
  private MembershipRole role = MembershipRole.MEMBER;

  /**
   * The Kommandogruppe of this membership (REQ-ROLE-003), or {@code null}. Set only for {@link
   * MembershipRole#KOMMANDOLEITER}, {@link MembershipRole#STELLV_KOMMANDOLEITER} and {@link
   * MembershipRole#ENSIGN}; an Ensign without a group reports to the Staffelleitung.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "kommando_group_id")
  @ToString.Exclude
  private KommandoGroup kommandoGroup;

  /** When the membership was granted (UTC); defaults to the insert time at the database layer. */
  @Column(name = "joined_at", nullable = false)
  private Instant joinedAt;

  /**
   * Optimistic-lock counter so two admins concurrently editing the same membership row (e.g. one
   * flipping {@code is_logistician}, another flipping {@code is_mission_manager}) surface a 409
   * Conflict at flush time rather than silently losing one of the writes. Initialised to {@code 0L}
   * by the V95 backfill; Hibernate bumps it on every UPDATE.
   */
  @Version private Long version;

  /**
   * Insert-time audit timestamp populated by Hibernate's {@code @CreationTimestamp}. Mirrors the
   * pattern from {@link AbstractEntity#getCreatedAt()}; not inherited because this entity uses a
   * composite key rather than a single-UUID id.
   */
  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private Instant createdAt;

  /**
   * Last-update audit timestamp populated by Hibernate's {@code @UpdateTimestamp}. Mirrors the
   * pattern from {@link AbstractEntity#getUpdatedAt()}; not inherited because this entity uses a
   * composite key rather than a single-UUID id.
   */
  @UpdateTimestamp
  @Column(name = "updated_at")
  private Instant updatedAt;
}
