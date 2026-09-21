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

package de.greluc.krt.profit.basetool.backend.model.scwiki;

import de.greluc.krt.profit.basetool.backend.model.AbstractEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * One named build slot of a {@link Blueprint} (SC Wiki {@code blueprint_requirement_group}), e.g.
 * {@code FRAME} or {@code EMITTER}. It bundles the ingredient(s) that fill the slot (the {@link
 * BlueprintIngredient}s back-referencing this group via {@link #ingredients}) with the stat
 * contributions that slot makes to the crafted item ({@link #modifiers}).
 *
 * <p>The group owns its {@link #modifiers} (cascade + orphan removal); the ingredient rows are
 * owned by the {@link Blueprint} aggregate, so {@link #ingredients} is a read-only inverse view.
 * Captured from the blueprint detail endpoint by {@code ScWikiBlueprintSyncService}, which rebuilds
 * the group graph in place on each run.
 */
@Entity
@Table(name = "blueprint_requirement_group")
@Getter
@Setter
@ToString(exclude = {"blueprint", "modifiers", "ingredients"})
@NoArgsConstructor
public class BlueprintRequirementGroup extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning blueprint. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blueprint_id", nullable = false)
  private Blueprint blueprint;

  /** Position within the blueprint's requirement-group list (drives {@code @OrderBy}). */
  @Column(name = "order_index", nullable = false)
  private Integer orderIndex;

  /** Wiki internal key of the slot, e.g. {@code "EMITTER"}. */
  @Column(name = "group_key")
  private String groupKey;

  /** Display name of the slot, e.g. {@code "Emitter"}. */
  @Column(name = "name")
  private String name;

  /** Wiki node kind; always {@code "group"} for a requirement group. */
  @Column(name = "kind", length = 16)
  private String kind;

  /** Number of children that must be fulfilled within the group. */
  @Column(name = "required_count")
  private Integer requiredCount;

  /** Ordered stat-modifier lines (the stats this slot contributes). Owned with cascade + orphan. */
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  @OneToMany(
      mappedBy = "requirementGroup",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("orderIndex ASC")
  @BatchSize(size = 64)
  private List<BlueprintRequirementModifier> modifiers = new ArrayList<>();

  /**
   * Ingredient lines that fill this slot. Read-only inverse of {@link
   * BlueprintIngredient#getRequirementGroup()} — the {@link Blueprint} owns the ingredient
   * lifecycle, so this view is never mutated through the group.
   */
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  @OneToMany(mappedBy = "requirementGroup", fetch = FetchType.LAZY)
  @OrderBy("orderIndex ASC")
  @BatchSize(size = 64)
  private List<BlueprintIngredient> ingredients = new ArrayList<>();

  /**
   * Returns the ordered modifier lines as an unmodifiable view. Mutate through {@link
   * #addModifier}.
   *
   * @return an unmodifiable view of the modifier lines
   */
  @NotNull
  @UnmodifiableView
  public List<BlueprintRequirementModifier> getModifiers() {
    return Collections.unmodifiableList(modifiers);
  }

  /**
   * Appends a modifier line and sets its back-reference to this group.
   *
   * @param modifier the line to add
   */
  public void addModifier(@NotNull BlueprintRequirementModifier modifier) {
    modifier.setRequirementGroup(this);
    modifiers.add(modifier);
  }

  /**
   * Returns the ingredient lines filling this slot as an unmodifiable view (read-only inverse).
   *
   * @return an unmodifiable view of the ingredient lines belonging to this group
   */
  @NotNull
  @UnmodifiableView
  public List<BlueprintIngredient> getIngredients() {
    return Collections.unmodifiableList(ingredients);
  }
}
