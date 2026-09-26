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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Generated;

/** Job Order JPA entity. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "job_order")
@BatchSize(size = 100)
public class JobOrder extends AbstractEntity<UUID> {

  /**
   * Maximum length, in characters, of the optional {@link #comment}. The same bound is enforced at
   * the DTO validation boundary ({@code @Size}), in the {@code comment} column definition, and by
   * the Flyway migration, so the cap holds end-to-end for untrusted (incl. anonymous) input.
   */
  public static final int COMMENT_MAX_LENGTH = 1000;

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "display_id", insertable = false, updatable = false)
  @Generated
  private Integer displayId;

  /**
   * The profit-eligible squadron or Spezialkommando that processes this order. It governs
   * visibility: a squadron-responsible order is private to that squadron and admins, an
   * SK-responsible order is public. Changed only via {@code PATCH
   * /api/v1/orders/{id}/responsible-org-unit}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "responsible_org_unit_id", nullable = false)
  private OrgUnit responsibleOrgUnit;

  /**
   * The org unit that placed the order and receives the goods; any squadron or Spezialkommando.
   * Mandatory and editable by Logistician+, but it grants no visibility.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "requesting_org_unit_id", nullable = false)
  private OrgUnit requestingOrgUnit;

  @Column private String handle;

  @Column private Integer priority;

  /**
   * Optional, untrusted free-text comment from the order creator, at most {@value
   * #COMMENT_MAX_LENGTH} characters. Always rendered HTML-escaped and never logged; {@code null}
   * when left empty.
   */
  @Column(name = "comment", length = COMMENT_MAX_LENGTH)
  private String comment;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  private JobOrderStatus status = JobOrderStatus.OPEN;

  /**
   * Order kind. {@link JobOrderType#MATERIAL} (the default, and the value backfilled onto every
   * pre-existing row by migration V123) requests raw materials directly via {@link #materials};
   * {@link JobOrderType#ITEM} requests finished items via {@link #items}, from which the material
   * requirements are derived. Never {@code null}.
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

  /**
   * Users who signed up to work on this order (the "Bearbeiter"), each as a {@link
   * JobOrderAssignee} edge that additionally carries the assignee's optional note. A
   * {@code @OneToMany} child collection (not a plain {@code @ManyToMany}) since the V147 promotion
   * so the per-assignee note + version live on the edge.
   */
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
