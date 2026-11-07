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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDeletionObserver;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * An admin's edit of a P4K-known material's resource group or refined material becomes an override,
 * and the admin hands it back to the P4K import; both are audited (REQ-P4K-024, REQ-P4K-025).
 */
@ExtendWith(MockitoExtension.class)
class MaterialP4kOverrideServiceTest {

  @Mock private MaterialRepository materialRepository;
  @Mock private MaterialPriceRepository materialPriceRepository;
  @Mock private MaterialCategoryRepository materialCategoryRepository;
  @Mock private MaterialMapper materialMapper;
  @Mock private MaterialDeletionObserver materialDeletionObserver;
  @Mock private AuditRecorder auditRecorder;

  @InjectMocks private MaterialService materialService;

  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000f0001");

  private static MaterialCategory category(@NotNull String name) {
    MaterialCategory category = new MaterialCategory();
    category.setId(UUID.randomUUID());
    category.setName(name);
    return category;
  }

  private static Material stored(@Nullable UUID p4kUuid, @Nullable MaterialCategory category) {
    Material material = new Material();
    material.setId(ID);
    material.setName("Borase");
    material.setType(MaterialType.RAW);
    material.setP4kUuid(p4kUuid);
    material.setCategory(category);
    material.setVersion(4L);
    return material;
  }

  private static Material edit(@Nullable MaterialCategory category) {
    Material update = new Material();
    update.setName("Borase");
    update.setType(MaterialType.RAW);
    update.setCategory(category);
    update.setVersion(4L);
    return update;
  }

  private void storing(@NotNull Material material) {
    when(materialRepository.findById(ID)).thenReturn(Optional.of(material));
  }

  @Test
  void changingTheGroupOfAP4kKnownMaterial_setsTheOverride_andAuditsIt() {
    MaterialCategory metal = category("Metal");
    Material borase = stored(UUID.randomUUID(), category("Mineral"));
    storing(borase);
    when(materialCategoryRepository.findById(metal.getId())).thenReturn(Optional.of(metal));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    materialService.updateMaterial(ID, edit(metal));

    assertThat(borase.isResourceGroupManual()).isTrue();
    assertThat(borase.isRefinedMaterialManual()).isFalse();
    verify(auditRecorder)
        .record(
            eq(AuditEventType.CATALOGUE_MATERIAL_P4K_OVERRIDE_SET),
            eq(ID),
            eq("Borase"),
            isNull(),
            argThat(details ->
                ("field=RESOURCE_GROUP valueId=" + metal.getId()).equals(String.valueOf(details))));
  }

  @Test
  void changingTheGroupOfAMaterialTheP4kDoesNotKnow_setsNoOverride() {
    MaterialCategory metal = category("Metal");
    Material manual = stored(null, null);
    storing(manual);
    when(materialCategoryRepository.findById(metal.getId())).thenReturn(Optional.of(metal));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    materialService.updateMaterial(ID, edit(metal));

    assertThat(manual.isResourceGroupManual()).isFalse();
    verify(auditRecorder, never())
        .record(eq(AuditEventType.CATALOGUE_MATERIAL_P4K_OVERRIDE_SET), any(), any(), any(), any());
  }

  @Test
  void anEditLeavingTheGroup_orOfAnOverriddenGroup_recordsNoSecondOverride() {
    MaterialCategory mineral = category("Mineral");
    Material borase = stored(UUID.randomUUID(), mineral);
    borase.setRefinedMaterialManual(true);
    storing(borase);
    when(materialCategoryRepository.findById(mineral.getId())).thenReturn(Optional.of(mineral));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    materialService.updateMaterial(ID, edit(mineral));

    assertThat(borase.isResourceGroupManual()).isFalse();
    assertThat(borase.isRefinedMaterialManual()).isTrue();
    verify(auditRecorder, never())
        .record(eq(AuditEventType.CATALOGUE_MATERIAL_P4K_OVERRIDE_SET), any(), any(), any(), any());
  }

  @Test
  void releasingAnOverride_clearsIt_andAuditsIt() {
    Material borase = stored(UUID.randomUUID(), category("Metal"));
    borase.setResourceGroupManual(true);
    storing(borase);
    when(materialRepository.saveAndFlush(borase)).thenReturn(borase);

    Material released =
        materialService.releaseP4kOverride(ID, MaterialP4kOverride.RESOURCE_GROUP, 4L);

    assertThat(released.isResourceGroupManual()).isFalse();
    verify(auditRecorder)
        .record(
            eq(AuditEventType.CATALOGUE_MATERIAL_P4K_OVERRIDE_RELEASED),
            eq(ID),
            eq("Borase"),
            isNull(),
            argThat(details -> "field=RESOURCE_GROUP".equals(String.valueOf(details))));
  }

  @Test
  void releasingAFieldWithoutOverride_changesNothing() {
    Material borase = stored(UUID.randomUUID(), null);
    storing(borase);

    materialService.releaseP4kOverride(ID, MaterialP4kOverride.REFINED_MATERIAL, 4L);

    verify(materialRepository, never()).saveAndFlush(any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void releasingWithAStaleVersion_isRefused() {
    Material borase = stored(UUID.randomUUID(), null);
    borase.setRefinedMaterialManual(true);
    storing(borase);

    assertThatThrownBy(
            () -> materialService.releaseP4kOverride(ID, MaterialP4kOverride.REFINED_MATERIAL, 3L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(borase.isRefinedMaterialManual()).isTrue();
  }
}
