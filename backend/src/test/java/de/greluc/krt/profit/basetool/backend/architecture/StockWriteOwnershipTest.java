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

package de.greluc.krt.profit.basetool.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.architecture.fixture.StockWriteFixtures;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.service.AllocationReductions;
import de.greluc.krt.profit.basetool.backend.service.UserDeletionService;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;

/**
 * Only the inventory module writes Lager rows (plan §7.5, P3-5c): every other module asks {@code
 * inventory.api.StockCommands}. A write is a mutating call on {@link InventoryItemRepository}
 * ({@code save*}, {@code delete*} or a {@code @Modifying} query), a setter of a Lager row or its
 * earmarks, or a mutator of {@link InventoryAllocations} or {@link AllocationReductions}.
 *
 * <p>The rule is proven able to fail on {@link StockWriteFixtures}.
 */
class StockWriteOwnershipTest {

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend");

  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackagesOf(StockWriteFixtures.class);

  /** Writers outside the inventory module, each with the reason. */
  private static final Map<String, String> FOREIGN_WRITERS =
      Map.of(
          UserDeletionService.class.getName(),
          "the GDPR orchestrator purges the member's Lager rows; it becomes an erasure participant"
              + " of the inventory module in Phase 4 (plan §7.6)");

  private static final Set<String> ROW_TYPES =
      Set.of(
          InventoryItem.class.getName(),
          InventoryJobOrderAllocation.class.getName(),
          InventoryMissionAllocation.class.getName());

  private static final Set<String> ALLOCATION_MUTATORS =
      Set.of("addJobOrder", "addMission", "reduceJobOrder", "reduceMission", "unionInto");

  @Test
  @DisplayName("only the inventory module writes Lager rows")
  void onlyTheInventoryModuleWritesLagerRows() {
    assertThat(foreignWriters(PRODUCTION))
        .as(
            """
            Classes outside the inventory module that write Lager rows, against FOREIGN_WRITERS. \
            Ask inventory.api.StockCommands instead.\
            """)
        .isEqualTo(new TreeSet<>(FOREIGN_WRITERS.keySet()));
  }

  @Test
  @DisplayName("proof: a planted foreign Lager writer is reported, its read is not")
  void plantedWritesAreReported() {
    String owner = StockWriteFixtures.PlantedStockWriter.class.getName();
    assertThat(writingMethods(FIXTURES))
        .containsExactlyInAnyOrder(
            owner + ".saves",
            owner + ".setsTheAmount",
            owner + ".earmarks",
            owner + ".releasesInBulk");
  }

  private static Set<String> foreignWriters(JavaClasses classes) {
    Set<String> writers = new TreeSet<>();
    for (JavaClass javaClass : classes) {
      if (isInventory(javaClass)) {
        continue;
      }
      for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
        if (isWrite(call)) {
          writers.add(topLevel(javaClass).getName());
          break;
        }
      }
    }
    return writers;
  }

  private static Set<String> writingMethods(JavaClasses classes) {
    Set<String> methods = new TreeSet<>();
    for (JavaClass javaClass : classes) {
      for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
        if (isWrite(call)) {
          methods.add(javaClass.getName() + "." + call.getOrigin().getName());
        }
      }
    }
    return methods;
  }

  private static boolean isWrite(JavaMethodCall call) {
    String owner = call.getTargetOwner().getName();
    String name = call.getName();
    if (owner.equals(InventoryItemRepository.class.getName())) {
      return name.startsWith("save")
          || name.startsWith("delete")
          || call.getTarget()
              .resolveMember()
              .map(m -> m.isAnnotatedWith(Modifying.class))
              .orElse(false);
    }
    if (ROW_TYPES.contains(owner)) {
      return name.startsWith("set");
    }
    if (owner.equals(InventoryAllocations.class.getName())) {
      return ALLOCATION_MUTATORS.contains(name);
    }
    return owner.equals(AllocationReductions.class.getName()) && name.equals("applyPlan");
  }

  private static boolean isInventory(JavaClass javaClass) {
    JavaClass top = topLevel(javaClass);
    String simple = top.getSimpleName();
    return top.getPackageName().startsWith("de.greluc.krt.profit.basetool.backend.inventory")
        || ROW_TYPES.contains(top.getName())
        || simple.startsWith("Inventory")
        || simple.equals(AllocationReductions.class.getSimpleName());
  }

  private static JavaClass topLevel(JavaClass javaClass) {
    JavaClass current = javaClass;
    while (current.getEnclosingClass().isPresent()) {
      current = current.getEnclosingClass().get();
    }
    return current;
  }
}
