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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.EntityInUseException;
import de.greluc.krt.profit.basetool.backend.mapper.QualityTierMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.QualityTier;
import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierWriteDto;
import de.greluc.krt.profit.basetool.backend.repository.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.support.QualityTierFixtures;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Unit tests for {@link QualityTierService} (REQ-ORDERS-036). */
@ExtendWith(MockitoExtension.class)
class QualityTierServiceTest {

  @Mock private QualityTierRepository repository;
  @Mock private AuditService auditService;

  private QualityTierService service;
  private QualityTier none;
  private QualityTier good;

  @BeforeEach
  void setUp() {
    service =
        new QualityTierService(
            repository, Mappers.getMapper(QualityTierMapper.class), auditService);
    none = QualityTierFixtures.none();
    good = QualityTierFixtures.good();
  }

  @Test
  void resolve_byCode_isCaseInsensitive() {
    when(repository.findByCode("GOOD")).thenReturn(Optional.of(good));

    assertThat(service.resolveForRequirement("good", null, Set.of())).isSameAs(good);
  }

  @Test
  void resolve_byFloor_whenNoCode() {
    when(repository.findByMinQuality(650)).thenReturn(Optional.of(good));

    assertThat(service.resolveForRequirement(null, 650, Set.of())).isSameAs(good);
  }

  @Test
  void resolve_withNothing_isTheBaseTier() {
    when(repository.findByMinQuality(0)).thenReturn(Optional.of(none));

    assertThat(service.resolveForRequirement(null, null, Set.of())).isSameAs(none);
  }

  @Test
  void resolve_unknownCodeOrFloor_isABadRequest() {
    when(repository.findByCode("MYTHIC")).thenReturn(Optional.empty());
    when(repository.findByMinQuality(777)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolveForRequirement("MYTHIC", null, Set.of()))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.resolveForRequirement(null, 777, Set.of()))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void resolve_inactiveTier_isRefusedUnlessKept() {
    QualityTier legacy = QualityTierFixtures.tier("Q800", 800);
    legacy.setActive(false);
    when(repository.findByMinQuality(800)).thenReturn(Optional.of(legacy));

    assertThatThrownBy(() -> service.resolveForRequirement(null, 800, Set.of()))
        .isInstanceOf(BadRequestException.class);
    assertThat(service.resolveForRequirement(null, 800, Set.of(legacy.getId()))).isSameAs(legacy);
  }

  @Test
  void defaultForIngredient_picksTheHighestActiveFloorReached() {
    QualityTier excellent = QualityTierFixtures.tier("EXCELLENT", 900);
    QualityTier retired = QualityTierFixtures.tier("Q800", 800);
    retired.setActive(false);
    when(repository.findAllByOrderByMinQualityAsc())
        .thenReturn(List.of(none, good, retired, excellent));

    assertThat(service.defaultForIngredient(850)).isSameAs(good);
    assertThat(service.defaultForIngredient(950)).isSameAs(excellent);
    assertThat(service.defaultForIngredient(649)).isSameAs(none);
    assertThat(service.defaultForIngredient(null)).isSameAs(none);
  }

  @Test
  void listActive_hidesInactive_andSortsBySortOrder() {
    QualityTier retired = QualityTierFixtures.tier("Q800", 800);
    retired.setActive(false);
    when(repository.findAll()).thenReturn(List.of(good, retired, none));

    assertThat(service.listActive()).extracting("code").containsExactly("NONE", "GOOD");
    assertThat(service.listAll()).extracting("code").containsExactly("NONE", "GOOD", "Q800");
  }

  @Test
  void create_persistsAndAudits() {
    when(repository.findByCode("EXCELLENT")).thenReturn(Optional.empty());
    when(repository.findByMinQuality(900)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(QualityTier.class)))
        .thenAnswer(
            inv -> {
              QualityTier tier = inv.getArgument(0);
              tier.setId(UUID.randomUUID());
              return tier;
            });

    var dto =
        service.create(
            new QualityTierWriteDto("EXCELLENT", 900, " Exzellent ", "Excellent", 900, true, null));

    assertThat(dto.labelDe()).isEqualTo("Exzellent");
    verify(auditService)
        .record(eq(AuditEventType.QUALITY_TIER_CREATED), any(), eq("EXCELLENT"), any(), any());
  }

  @Test
  void create_duplicateFloor_isRefused() {
    when(repository.findByCode("OTHER")).thenReturn(Optional.empty());
    when(repository.findByMinQuality(650)).thenReturn(Optional.of(good));

    assertThatThrownBy(
            () -> service.create(new QualityTierWriteDto("OTHER", 650, "a", "b", 1, true, null)))
        .isInstanceOf(DuplicateEntityException.class);
  }

  @Test
  void create_inactiveBaseTier_isRefused() {
    when(repository.findByCode("ZERO")).thenReturn(Optional.empty());
    when(repository.findByMinQuality(0)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.create(new QualityTierWriteDto("ZERO", 0, "a", "b", 0, false, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void update_floorOfAReferencedTier_isAConflict() {
    when(repository.findById(good.getId())).thenReturn(Optional.of(good));
    when(repository.findByCode("GOOD")).thenReturn(Optional.of(good));
    when(repository.findByMinQuality(700)).thenReturn(Optional.empty());
    when(repository.countReferences(good.getId())).thenReturn(3L);

    assertThatThrownBy(
            () ->
                service.update(
                    good.getId(),
                    new QualityTierWriteDto("GOOD", 700, "Gut", "Good", 650, true, 0L)))
        .isInstanceOf(BusinessConflictException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void update_staleVersion_isAConflict() {
    when(repository.findById(good.getId())).thenReturn(Optional.of(good));

    assertThatThrownBy(
            () ->
                service.update(
                    good.getId(),
                    new QualityTierWriteDto("GOOD", 650, "Gut", "Good", 650, true, 7L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void update_deactivation_isAuditedAsDeactivated() {
    when(repository.findById(good.getId())).thenReturn(Optional.of(good));
    when(repository.findByCode("GOOD")).thenReturn(Optional.of(good));
    when(repository.findByMinQuality(650)).thenReturn(Optional.of(good));
    when(repository.saveAndFlush(good)).thenReturn(good);

    service.update(
        good.getId(), new QualityTierWriteDto("GOOD", 650, "Gut (650+)", "Good", 650, false, 0L));

    assertThat(good.isActive()).isFalse();
    verify(auditService)
        .record(eq(AuditEventType.QUALITY_TIER_DEACTIVATED), any(), eq("GOOD"), any(), any());
  }

  @Test
  void delete_referencedTier_isInUse() {
    when(repository.findById(good.getId())).thenReturn(Optional.of(good));
    when(repository.countReferences(good.getId())).thenReturn(1L);

    assertThatThrownBy(() -> service.delete(good.getId())).isInstanceOf(EntityInUseException.class);
    verify(repository, never()).delete(any());
  }

  @Test
  void delete_baseTier_isRefused() {
    when(repository.findById(none.getId())).thenReturn(Optional.of(none));

    assertThatThrownBy(() -> service.delete(none.getId()))
        .isInstanceOf(BusinessConflictException.class);
  }

  @Test
  void delete_unusedTier_deletesAndAudits() {
    QualityTier unused = QualityTierFixtures.tier("EXCELLENT", 900);
    when(repository.findById(unused.getId())).thenReturn(Optional.of(unused));
    when(repository.countReferences(unused.getId())).thenReturn(0L);

    service.delete(unused.getId());

    verify(repository).delete(unused);
    verify(auditService)
        .record(
            eq(AuditEventType.QUALITY_TIER_DELETED),
            eq(unused.getId()),
            eq("EXCELLENT"),
            any(),
            any());
  }
}
