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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExternalAliasWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for {@link MaterialExternalAliasService}.
 *
 * <p>Coverage for the four behaviours that matter for the R3 sync's correctness: lookup chain uses
 * the case-insensitive alias resolver, create / update reject duplicates — including case-only
 * variants (REQ-REFINERY-010) — create / update reject a missing material, and update enforces the
 * optimistic-lock version.
 */
@ExtendWith(MockitoExtension.class)
class MaterialExternalAliasServiceTest {

  @Mock private MaterialExternalAliasRepository repository;
  @Mock private MaterialRepository materialRepository;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private MaterialExternalAliasService service;

  private Material silicon;
  private UUID siliconId;
  private UUID aliasId;

  @BeforeEach
  void setUp() {
    siliconId = UUID.randomUUID();
    silicon = new Material();
    silicon.setId(siliconId);
    silicon.setName("Silicon (Raw)");
    aliasId = UUID.randomUUID();
  }

  @Test
  void findAll_delegatesToRepositoryOrderedByExternalName() {
    MaterialExternalAlias alias = newAlias("Raw Silicon");
    when(repository.findAllByOrderByExternalNameAsc()).thenReturn(List.of(alias));

    List<MaterialExternalAlias> result = service.findAll();

    assertEquals(1, result.size());
    assertSame(alias, result.get(0));
  }

  @Test
  void findById_throwsNotFound_whenMissing() {
    when(repository.findById(aliasId)).thenReturn(Optional.empty());

    NotFoundException ex = assertThrows(NotFoundException.class, () -> service.findById(aliasId));

    assertTrue(ex.getMessage().contains(aliasId.toString()));
  }

  @Test
  void resolveMaterialByAlias_returnsLinkedMaterial_onCaseInsensitiveMatch() {
    MaterialExternalAlias alias = newAlias("Raw Silicon");
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(
            MaterialExternalAliasSource.SCWIKI, "raw silicon"))
        .thenReturn(Optional.of(alias));

    Material result =
        service.resolveMaterialByAlias(MaterialExternalAliasSource.SCWIKI, "raw silicon");

    assertSame(silicon, result);
  }

  @Test
  void resolveMaterialByAlias_returnsNull_onBlankInputWithoutRepoLookup() {
    Material result = service.resolveMaterialByAlias(MaterialExternalAliasSource.SCWIKI, "  ");

    assertNull(result);
    verifyNoInteractions(repository);
  }

  @Test
  void create_stampsCreatedByFromJwt_andPersistsRow() {
    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "Raw Silicon", null, null, null, "verification note", null);
    when(materialRepository.findById(siliconId)).thenReturn(Optional.of(silicon));
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(
            MaterialExternalAliasSource.SCWIKI, "Raw Silicon"))
        .thenReturn(Optional.empty());
    Authentication auth = new UsernamePasswordAuthenticationToken("admin-sub", "n/a");
    when(authHelperService.currentAuthentication()).thenReturn(Optional.of(auth));
    when(repository.save(any(MaterialExternalAlias.class))).thenAnswer(inv -> inv.getArgument(0));

    MaterialExternalAlias saved = service.create(request);

    assertEquals("admin-sub", saved.getCreatedBy());
    assertSame(silicon, saved.getMaterial());
    assertEquals(MaterialExternalAliasSource.SCWIKI, saved.getSourceSystem());
    assertEquals("Raw Silicon", saved.getExternalName());
    assertEquals("verification note", saved.getNote());
  }

  @Test
  void create_defaultsCreatedByToSystem_whenNoAuthenticationPresent() {
    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "Raw Silicon", null, null, null, null, null);
    when(materialRepository.findById(siliconId)).thenReturn(Optional.of(silicon));
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(any(), any()))
        .thenReturn(Optional.empty());
    when(authHelperService.currentAuthentication()).thenReturn(Optional.empty());
    when(repository.save(any(MaterialExternalAlias.class))).thenAnswer(inv -> inv.getArgument(0));

    MaterialExternalAlias saved = service.create(request);

    assertEquals("system", saved.getCreatedBy());
  }

  @Test
  void create_throwsNotFound_whenMaterialMissing() {
    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "Raw Silicon", null, null, null, null, null);
    when(materialRepository.findById(siliconId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.create(request));
    verify(repository, never()).save(any());
  }

  @Test
  void create_throwsDuplicate_whenAliasForSameSourceAndNameExists() {
    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "Raw Silicon", null, null, null, null, null);
    when(materialRepository.findById(siliconId)).thenReturn(Optional.of(silicon));
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(
            MaterialExternalAliasSource.SCWIKI, "Raw Silicon"))
        .thenReturn(Optional.of(newAlias("Raw Silicon")));

    assertThrows(DuplicateEntityException.class, () -> service.create(request));
    verify(repository, never()).save(any());
  }

  @Test
  void create_throwsDuplicate_whenCaseVariantOfExistingAliasIsSubmitted() {
    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "STILERON (ORE)", null, null, null, null, null);
    when(materialRepository.findById(siliconId)).thenReturn(Optional.of(silicon));
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(
            MaterialExternalAliasSource.SCWIKI, "STILERON (ORE)"))
        .thenReturn(Optional.of(newAlias("Stileron (Ore)")));

    DuplicateEntityException ex =
        assertThrows(DuplicateEntityException.class, () -> service.create(request));

    assertTrue(ex.getMessage().contains("Stileron (Ore)"));
    verify(repository, never()).save(any());
  }

  @Test
  void update_throwsOptimisticLock_onVersionMismatch() {
    MaterialExternalAlias existing = newAlias("Raw Silicon");
    existing.setId(aliasId);
    existing.setVersion(5L);
    when(repository.findById(aliasId)).thenReturn(Optional.of(existing));

    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "Raw Silicon", null, null, null, null, 4L);

    assertThrows(
        ObjectOptimisticLockingFailureException.class, () -> service.update(aliasId, request));
    verify(repository, never()).save(any());
  }

  @Test
  void update_acceptsCorrectVersion_andPersistsChanges() {
    MaterialExternalAlias existing = newAlias("Raw Silicon");
    existing.setId(aliasId);
    existing.setVersion(7L);
    when(repository.findById(aliasId)).thenReturn(Optional.of(existing));
    when(materialRepository.findById(siliconId)).thenReturn(Optional.of(silicon));
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(
            MaterialExternalAliasSource.SCWIKI, "Raw Silicon"))
        .thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(MaterialExternalAlias.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "Raw Silicon", "wiki-key", null, "AGRI", "updated note", 7L);

    MaterialExternalAlias saved = service.update(aliasId, request);

    assertEquals("wiki-key", saved.getExternalKey());
    assertEquals("AGRI", saved.getExternalCode());
    assertEquals("updated note", saved.getNote());
  }

  @Test
  void update_throwsDuplicate_whenAnotherRowAlreadyHoldsTheSourceAndName() {
    MaterialExternalAlias existing = newAlias("Old Name");
    existing.setId(aliasId);
    existing.setVersion(0L);
    when(repository.findById(aliasId)).thenReturn(Optional.of(existing));
    when(materialRepository.findById(siliconId)).thenReturn(Optional.of(silicon));

    MaterialExternalAlias other = newAlias("Raw Silicon");
    other.setId(UUID.randomUUID());
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(
            MaterialExternalAliasSource.SCWIKI, "Raw Silicon"))
        .thenReturn(Optional.of(other));

    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "Raw Silicon", null, null, null, null, 0L);

    assertThrows(DuplicateEntityException.class, () -> service.update(aliasId, request));
    verify(repository, never()).save(any());
  }

  @Test
  void update_throwsDuplicate_whenCaseVariantOfAnotherRowExists() {
    MaterialExternalAlias existing = newAlias("Old Name");
    existing.setId(aliasId);
    existing.setVersion(0L);
    when(repository.findById(aliasId)).thenReturn(Optional.of(existing));
    when(materialRepository.findById(siliconId)).thenReturn(Optional.of(silicon));

    MaterialExternalAlias other = newAlias("Stileron (Ore)");
    other.setId(UUID.randomUUID());
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(
            MaterialExternalAliasSource.SCWIKI, "STILERON (ORE)"))
        .thenReturn(Optional.of(other));

    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "STILERON (ORE)", null, null, null, null, 0L);

    assertThrows(DuplicateEntityException.class, () -> service.update(aliasId, request));
    verify(repository, never()).save(any());
  }

  @Test
  void update_allowsRecasingOwnName_withoutDuplicateError() {
    MaterialExternalAlias existing = newAlias("stileron (ore)");
    existing.setId(aliasId);
    existing.setVersion(0L);
    when(repository.findById(aliasId)).thenReturn(Optional.of(existing));
    when(materialRepository.findById(siliconId)).thenReturn(Optional.of(silicon));
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(
            MaterialExternalAliasSource.SCWIKI, "Stileron (Ore)"))
        .thenReturn(Optional.of(existing));
    when(repository.saveAndFlush(any(MaterialExternalAlias.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "Stileron (Ore)", null, null, null, null, 0L);

    MaterialExternalAlias saved = service.update(aliasId, request);

    assertEquals("Stileron (Ore)", saved.getExternalName());
  }

  /**
   * Pins that {@code update} returns the entity from a {@code saveAndFlush}, not a plain {@code
   * save}: the alias edit form writes the returned {@code @Version} back into its hidden version
   * input in place (no reload), so a stale {@code save} version would 409 the next consecutive edit
   * of the same alias. The create path (unchanged) still uses {@code save}.
   */
  @Test
  void update_flushesSoReturnedVersionIsFresh() {
    MaterialExternalAlias existing = newAlias("Raw Silicon");
    existing.setId(aliasId);
    existing.setVersion(7L);
    when(repository.findById(aliasId)).thenReturn(Optional.of(existing));
    when(materialRepository.findById(siliconId)).thenReturn(Optional.of(silicon));
    when(repository.findBySourceSystemAndExternalNameIgnoreCase(
            MaterialExternalAliasSource.SCWIKI, "Raw Silicon"))
        .thenReturn(Optional.empty());
    when(repository.saveAndFlush(existing)).thenReturn(existing);

    MaterialExternalAliasWriteRequest request =
        new MaterialExternalAliasWriteRequest(
            siliconId, "SCWIKI", "Raw Silicon", null, null, null, null, 7L);

    service.update(aliasId, request);

    verify(repository).saveAndFlush(existing);
    verify(repository, never()).save(existing);
  }

  @Test
  void delete_removesRow_whenPresent() {
    MaterialExternalAlias alias = newAlias("Raw Silicon");
    alias.setId(aliasId);
    when(repository.findById(aliasId)).thenReturn(Optional.of(alias));

    service.delete(aliasId);

    verify(repository).delete(eq(alias));
  }

  private MaterialExternalAlias newAlias(String externalName) {
    MaterialExternalAlias alias = new MaterialExternalAlias();
    alias.setMaterial(silicon);
    alias.setSourceSystem(MaterialExternalAliasSource.SCWIKI);
    alias.setExternalName(externalName);
    return alias;
  }
}
