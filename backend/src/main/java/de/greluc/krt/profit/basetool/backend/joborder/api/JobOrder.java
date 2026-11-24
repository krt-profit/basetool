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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.TenantScoped;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.OptimisticLock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Job Order JPA entity. */
@TenantScoped("responsibleOrgUnit")
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "job_order")
@BatchSize(size = 100)
@DynamicUpdate
public class JobOrder extends AbstractEntity<UUID> {

  /**
   * Maximum length, in characters, of the optional {@link #comment}. The same bound is enforced at
   * the DTO validation boundary ({@code @Size}), in the {@code comment} column definition, and by
   * the Flyway migration, so the cap holds end-to-end for untrusted (incl. anonymous) input.
   */
  public static final int COMMENT_MAX_LENGTH = 1000;

  /** Maximum length, in characters, of the contact {@link #handle} every create and edit admits. */
  public static final int CONTACT_MAX_LENGTH = 200;

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "display_id", insertable = false, updatable = false)
  @Generated
  private Integer displayId;

  /** The current Profit-hierarchy processing unit, changed through the responsibility endpoint. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "responsible_org_unit_id", nullable = false)
  @OptimisticLock(excluded = true)
  private OrgUnit responsibleOrgUnit;

  /** The independently observed revision of the responsible unit. */
  @Column(name = "responsibility_version", nullable = false)
  @OptimisticLock(excluded = true)
  @Builder.Default
  private Long responsibilityVersion = 0L;

  /**
   * The customer unit granting complete order read and its current writer's requester capability.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "requesting_org_unit_id", nullable = false)
  private OrgUnit requestingOrgUnit;

  /** Verified Profit boundary at creation; null preserves unknown legacy provenance. */
  @Column(name = "original_profit_org_unit_id")
  @Nullable
  private UUID originalProfitOrgUnitId;

  @Column private String handle;

  @Column
  @Nullable
  @OptimisticLock(excluded = true)
  private Integer priority;

  /**
   * Optional, untrusted free-text comment from the order creator, at most {@value
   * #COMMENT_MAX_LENGTH} characters. Always rendered HTML-escaped and never logged; {@code null}
   * when left empty. Guarded by {@link #commentVersion}, not by the order version.
   */
  @Column(name = "comment", length = COMMENT_MAX_LENGTH)
  @Nullable
  @OptimisticLock(excluded = true)
  private String comment;

  /** The independently observed revision of the {@link #comment}, for every order kind. */
  @Column(name = "comment_version", nullable = false)
  @OptimisticLock(excluded = true)
  @Builder.Default
  private long commentVersion = 0L;

  /**
   * The registry client the {@link #comment} was last written through, or {@code null} while its
   * last write came from the Basetool; it moves with {@link #commentVersion} (REQ-ORDERS-078).
   */
  @Column(name = "comment_client_id", length = 63)
  @Nullable
  @OptimisticLock(excluded = true)
  private String commentClientId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  @OptimisticLock(excluded = true)
  private JobOrderStatus status = JobOrderStatus.OPEN;

  /**
   * When the order entered a terminal status, or {@code null} while it is active (REQ-ORDERS-054);
   * kept in step by {@link #setStatus(JobOrderStatus)} and present exactly while the status is
   * terminal, which the database enforces.
   */
  @Column(name = "closed_at")
  @Nullable
  @Setter(AccessLevel.NONE)
  @OptimisticLock(excluded = true)
  private Instant closedAt;

  /** The independently observed revision of the order's lifecycle. */
  @Column(name = "lifecycle_version", nullable = false)
  @OptimisticLock(excluded = true)
  @Builder.Default
  private Long lifecycleVersion = 0L;

  /** The independently observed contact revision of an aUEC order. */
  @Column(name = "money_information_version", nullable = false)
  @OptimisticLock(excluded = true)
  @Builder.Default
  private long moneyInformationVersion = 0L;

  /**
   * The registry client the order was created through, or {@code null} for an order created in the
   * Basetool; set by {@link #recordClientCreate} and never changed (REQ-OXCH-004).
   */
  @Column(name = "created_by_client_id", length = 63)
  @Nullable
  @Setter(AccessLevel.NONE)
  @OptimisticLock(excluded = true)
  private String createdByClientId;

  /**
   * The member who created the order through a client, or {@code null} for a web create and once
   * the account is gone (REQ-OXCH-004).
   */
  @Column(name = "created_by_member_id")
  @Nullable
  @Setter(AccessLevel.NONE)
  @OptimisticLock(excluded = true)
  private UUID createdByMemberId;

  /**
   * The client's {@code Idempotency-Key} of the create, unique per client and member, or {@code
   * null} for a web create (REQ-OXCH-004).
   */
  @Column(name = "create_idempotency_key", length = 128)
  @Nullable
  @Setter(AccessLevel.NONE)
  @OptimisticLock(excluded = true)
  private String createIdempotencyKey;

  /**
   * Exclusive order kind: raw materials, finished items, or an independently tracked net aUEC
   * amount.
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  private JobOrderType type = JobOrderType.MATERIAL;

  /**
   * Whether the blueprint-coverage view of an {@link JobOrderType#ITEM} order counts cosmetic
   * variants of the ordered items. {@code true} (default) matches by variant family; {@code false}
   * requires the exact blueprint.
   */
  @Column(name = "count_blueprints_with_variants", nullable = false)
  @Builder.Default
  private boolean countBlueprintsWithVariants = true;

  @OneToMany(
      mappedBy = "jobOrder",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private Set<JobOrderMaterial> materials = new HashSet<>();

  /** Personal assignee edges carrying independently versioned optional notes. */
  @OneToMany(
      mappedBy = "jobOrder",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private Set<JobOrderAssignee> assignees = new HashSet<>();

  @OneToMany(
      mappedBy = "jobOrder",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private Set<JobOrderHandover> handovers = new HashSet<>();

  /**
   * Ordered finished-item lines, populated only for {@link JobOrderType#ITEM} orders. Empty for
   * material orders.
   */
  @OneToMany(
      mappedBy = "jobOrder",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private Set<JobOrderItem> items = new HashSet<>();

  /**
   * Item-handover fulfilment events, populated only for {@link JobOrderType#ITEM} orders. Empty for
   * material orders (which use {@link #handovers}).
   */
  @OneToMany(
      mappedBy = "jobOrder",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @Builder.Default
  private Set<JobOrderItemHandover> itemHandovers = new HashSet<>();

  /**
   * Sets the status and keeps {@link #closedAt} in step: a terminal status records the current
   * instant unless the order is already closed, so a change between terminal statuses keeps the
   * first closing instant, and an active status clears it.
   *
   * @param status the new status
   */
  public void setStatus(@NotNull JobOrderStatus status) {
    if (status.isActive()) {
      closedAt = null;
    } else if (closedAt == null) {
      closedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
    this.status = status;
  }

  /**
   * Records that a client created the new order for a member under the client's key; a retry under
   * the same client, member and key finds the order instead of creating another. A comment the
   * create carries is the client's first write of it.
   *
   * @param actor the creating member and the client they acted through
   * @param idempotencyKey the client's {@code Idempotency-Key} of the create
   */
  public void recordClientCreate(
      @NotNull JobOrderActor.Client actor, @NotNull String idempotencyKey) {
    createdByClientId = actor.clientId();
    createdByMemberId = actor.member();
    createIdempotencyKey = idempotencyKey;
    if (comment != null) {
      commentClientId = actor.clientId();
    }
  }

  /**
   * Adds a material and keeps the bidirectional back-reference in sync.
   *
   * @param material the child material row to attach; mutated so its {@code jobOrder} back-link
   *     points at this order.
   */
  public void addMaterial(JobOrderMaterial material) {
    materials.add(material);
    material.setJobOrder(this);
  }

  /**
   * Adds an assignee edge and keeps the bidirectional back-reference in sync.
   *
   * @param assignee the {@link JobOrderAssignee} edge to attach; mutated so its {@code jobOrder}
   *     back-link points at this order.
   */
  public void addAssignee(JobOrderAssignee assignee) {
    assignees.add(assignee);
    assignee.setJobOrder(this);
  }

  /**
   * Adds an ordered finished-item line and keeps the bidirectional back-reference in sync.
   *
   * @param item the child item line to attach; mutated so its {@code jobOrder} back-link points at
   *     this order.
   */
  public void addItem(JobOrderItem item) {
    items.add(item);
    item.setJobOrder(this);
  }

  /**
   * Adds an item-handover fulfilment event and keeps the bidirectional back-reference in sync.
   *
   * @param itemHandover the child handover to attach; mutated so its {@code jobOrder} back-link
   *     points at this order.
   */
  public void addItemHandover(JobOrderItemHandover itemHandover) {
    itemHandovers.add(itemHandover);
    itemHandover.setJobOrder(this);
  }
}
