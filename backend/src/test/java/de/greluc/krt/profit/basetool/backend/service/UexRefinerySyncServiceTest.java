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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.dto.uex.UexRefineryYieldDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexRefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.RefineryYield;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryYieldRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link UexRefinerySyncService}. Like the sibling UEX sync services, the contract
 * under test is:
 *
 * <ul>
 *   <li>Empty UEX response → service aborts early without wiping the local table.
 *   <li>Rows with missing identifiers are skipped (defensive: refining methods need a name; yields
 *       need both commodity-id and terminal-id plus a non-null value).
 *   <li>Existing rows are mutated in place (preserves id / version); new rows are created with the
 *       canonical fields.
 *   <li>Yield rows referencing an unknown material or terminal are skipped — never silently
 *       auto-created with placeholder data.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UexRefinerySyncServiceTest {

  @Mock private UexClient uexClient;
  @Mock private RefiningMethodRepository refiningMethodRepository;
  @Mock private RefineryYieldRepository refineryYieldRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private TerminalRepository terminalRepository;

  @Mock private AuditService auditService;
  @InjectMocks private UexRefinerySyncService service;

  // ── syncRefiningMethods ────────────────────────────────────────────────

  @Test
  void syncRefiningMethods_emptyResponse_abortsWithoutSaving() {
    when(uexClient.getRefineriesMethods()).thenReturn(List.of());

    service.syncRefiningMethods();

    verify(uexClient).getRefineriesMethods();
    verifyNoInteractions(refiningMethodRepository);
  }

  @Test
  void syncRefiningMethods_createsNewMethod_whenNoneExists() {
    UexRefiningMethodDto cormack =
        new UexRefiningMethodDto(1, "Cormack Method", "CORM", 95, 70, 30);
    when(uexClient.getRefineriesMethods()).thenReturn(List.of(cormack));
    when(refiningMethodRepository.findByName("Cormack Method")).thenReturn(Optional.empty());
    when(refiningMethodRepository.save(any(RefiningMethod.class)))
        .thenAnswer(i -> i.getArgument(0));

    service.syncRefiningMethods();

    ArgumentCaptor<RefiningMethod> cap = ArgumentCaptor.forClass(RefiningMethod.class);
    verify(refiningMethodRepository).save(cap.capture());
    RefiningMethod saved = cap.getValue();
    assertEquals("Cormack Method", saved.getName());
    assertEquals("CORM", saved.getCode());
    assertEquals(95, saved.getRatingYield());
    assertEquals(70, saved.getRatingCost());
    assertEquals(30, saved.getRatingSpeed());
    assertNull(saved.getId(), "new entity must NOT have a pre-set id");
  }

  @Test
  void syncRefiningMethods_updatesExistingMethodInPlace_preservingId() {
    // Given an existing row matched by name, with stale ratings
    UUID existingId = UUID.randomUUID();
    RefiningMethod existing = new RefiningMethod();
    existing.setId(existingId);
    existing.setName("Cormack Method");
    existing.setCode("OLD");
    existing.setRatingYield(0);
    existing.setVersion(3L);

    UexRefiningMethodDto fresh = new UexRefiningMethodDto(1, "Cormack Method", "CORM", 95, 70, 30);
    when(uexClient.getRefineriesMethods()).thenReturn(List.of(fresh));
    when(refiningMethodRepository.findByName("Cormack Method")).thenReturn(Optional.of(existing));
    when(refiningMethodRepository.save(any(RefiningMethod.class)))
        .thenAnswer(i -> i.getArgument(0));

    service.syncRefiningMethods();

    ArgumentCaptor<RefiningMethod> cap = ArgumentCaptor.forClass(RefiningMethod.class);
    verify(refiningMethodRepository).save(cap.capture());
    assertSame(existing, cap.getValue(), "existing entity must be mutated in place, not replaced");
    assertEquals(existingId, cap.getValue().getId());
    assertEquals(3L, cap.getValue().getVersion(), "JPA owns @Version — service must not touch it");
    assertEquals("CORM", cap.getValue().getCode());
    assertEquals(95, cap.getValue().getRatingYield());
  }

  @Test
  void syncRefiningMethods_skipsRowsWithoutName() {
    UexRefiningMethodDto missingName = new UexRefiningMethodDto(1, null, "X", 1, 1, 1);
    UexRefiningMethodDto blankName = new UexRefiningMethodDto(2, "  ", "Y", 1, 1, 1);
    when(uexClient.getRefineriesMethods()).thenReturn(List.of(missingName, blankName));

    service.syncRefiningMethods();

    // Name-less rows are pre-filtered, never reach the repo
    verify(refiningMethodRepository, never()).findByName(any());
    verify(refiningMethodRepository, never()).save(any());
  }

  @Test
  void syncRefiningMethods_processesMixedBatch() {
    // Given — one new, one update, one skipped
    UexRefiningMethodDto fresh = new UexRefiningMethodDto(1, "Fresh", "F", 1, 1, 1);
    UexRefiningMethodDto existing = new UexRefiningMethodDto(2, "Existing", "E", 2, 2, 2);
    UexRefiningMethodDto skipped = new UexRefiningMethodDto(3, "", null, 0, 0, 0);

    RefiningMethod existingEntity = new RefiningMethod();
    existingEntity.setId(UUID.randomUUID());
    existingEntity.setName("Existing");

    when(uexClient.getRefineriesMethods()).thenReturn(List.of(fresh, existing, skipped));
    when(refiningMethodRepository.findByName("Fresh")).thenReturn(Optional.empty());
    when(refiningMethodRepository.findByName("Existing")).thenReturn(Optional.of(existingEntity));
    when(refiningMethodRepository.save(any(RefiningMethod.class)))
        .thenAnswer(i -> i.getArgument(0));

    service.syncRefiningMethods();

    // Exactly two saves; the blank-name row never reached the repo
    verify(refiningMethodRepository, times(2)).save(any(RefiningMethod.class));
  }

  // ── syncRefineryYields ─────────────────────────────────────────────────

  @Test
  void syncRefineryYields_emptyResponse_abortsWithoutSaving() {
    when(uexClient.getRefineriesYields()).thenReturn(List.of());

    service.syncRefineryYields();

    verifyNoInteractions(refineryYieldRepository, materialRepository, terminalRepository);
  }

  @Test
  void syncRefineryYields_createsNewYield_whenMaterialAndTerminalKnown() {
    UUID materialId = UUID.randomUUID();
    UUID terminalId = UUID.randomUUID();
    Material material = new Material();
    material.setId(materialId);
    material.setIdCommodity(1);
    Terminal terminal = new Terminal();
    terminal.setId(terminalId);
    terminal.setIdTerminal(42);

    UexRefineryYieldDto payload = new UexRefineryYieldDto(100, 1, 42, 5);

    when(uexClient.getRefineriesYields()).thenReturn(List.of(payload));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(material));
    when(terminalRepository.findByIdTerminal(42)).thenReturn(Optional.of(terminal));
    when(refineryYieldRepository.findByTerminalIdAndMaterialId(terminalId, materialId))
        .thenReturn(Optional.empty());
    when(refineryYieldRepository.save(any(RefineryYield.class))).thenAnswer(i -> i.getArgument(0));

    service.syncRefineryYields();

    ArgumentCaptor<RefineryYield> cap = ArgumentCaptor.forClass(RefineryYield.class);
    verify(refineryYieldRepository).save(cap.capture());
    RefineryYield saved = cap.getValue();
    assertSame(material, saved.getMaterial());
    assertSame(terminal, saved.getTerminal());
    assertEquals(5, saved.getYieldBonus());
  }

  @Test
  void syncRefineryYields_updatesExistingYieldInPlace_preservingId() {
    UUID materialId = UUID.randomUUID();
    UUID terminalId = UUID.randomUUID();
    Material material = new Material();
    material.setId(materialId);
    material.setIdCommodity(1);
    Terminal terminal = new Terminal();
    terminal.setId(terminalId);
    terminal.setIdTerminal(42);

    UUID existingYieldId = UUID.randomUUID();
    RefineryYield existing = new RefineryYield();
    existing.setId(existingYieldId);
    existing.setMaterial(material);
    existing.setTerminal(terminal);
    existing.setYieldBonus(0);
    existing.setVersion(2L);

    UexRefineryYieldDto fresh = new UexRefineryYieldDto(100, 1, 42, 7);

    when(uexClient.getRefineriesYields()).thenReturn(List.of(fresh));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(material));
    when(terminalRepository.findByIdTerminal(42)).thenReturn(Optional.of(terminal));
    when(refineryYieldRepository.findByTerminalIdAndMaterialId(terminalId, materialId))
        .thenReturn(Optional.of(existing));
    when(refineryYieldRepository.save(any(RefineryYield.class))).thenAnswer(i -> i.getArgument(0));

    service.syncRefineryYields();

    ArgumentCaptor<RefineryYield> cap = ArgumentCaptor.forClass(RefineryYield.class);
    verify(refineryYieldRepository).save(cap.capture());
    assertSame(existing, cap.getValue());
    assertEquals(existingYieldId, cap.getValue().getId());
    assertEquals(2L, cap.getValue().getVersion());
    assertEquals(7, cap.getValue().getYieldBonus());
  }

  @Test
  void syncRefineryYields_skipsRowWithMissingIdentifiers() {
    UexRefineryYieldDto noCommodity = new UexRefineryYieldDto(1, null, 42, 5);
    UexRefineryYieldDto noTerminal = new UexRefineryYieldDto(2, 1, null, 5);
    UexRefineryYieldDto noValue = new UexRefineryYieldDto(3, 1, 42, null);

    when(uexClient.getRefineriesYields()).thenReturn(List.of(noCommodity, noTerminal, noValue));

    service.syncRefineryYields();

    // No row reached the repository
    verify(materialRepository, never()).findByIdCommodity(any());
    verify(terminalRepository, never()).findByIdTerminal(any());
    verify(refineryYieldRepository, never()).save(any());
  }

  @Test
  void syncRefineryYields_skipsRow_whenMaterialUnknown() {
    UexRefineryYieldDto orphan = new UexRefineryYieldDto(1, 999, 42, 5);
    when(uexClient.getRefineriesYields()).thenReturn(List.of(orphan));
    when(materialRepository.findByIdCommodity(999)).thenReturn(Optional.empty());

    service.syncRefineryYields();

    // No yield saved because the upstream commodity is not in our DB.
    // Yields are NEVER allowed to auto-create a placeholder material —
    // the commodity catalog is the source of truth.
    verify(refineryYieldRepository, never()).save(any());
  }

  @Test
  void syncRefineryYields_skipsRow_whenTerminalUnknown() {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setIdCommodity(1);

    UexRefineryYieldDto orphan = new UexRefineryYieldDto(1, 1, 9999, 5);
    when(uexClient.getRefineriesYields()).thenReturn(List.of(orphan));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(material));
    when(terminalRepository.findByIdTerminal(9999)).thenReturn(Optional.empty());

    service.syncRefineryYields();

    verify(refineryYieldRepository, never()).save(any());
  }

  @Test
  void syncRefineryYields_processesMixedBatch() {
    // Given — one new yield, one orphan (terminal unknown), one skipped (null value)
    UUID materialId = UUID.randomUUID();
    UUID terminalId = UUID.randomUUID();
    Material material = new Material();
    material.setId(materialId);
    material.setIdCommodity(1);
    Terminal terminal = new Terminal();
    terminal.setId(terminalId);
    terminal.setIdTerminal(42);

    UexRefineryYieldDto good = new UexRefineryYieldDto(1, 1, 42, 5);
    UexRefineryYieldDto orphan = new UexRefineryYieldDto(2, 1, 999, 5);
    UexRefineryYieldDto skipped = new UexRefineryYieldDto(3, 1, 42, null);

    when(uexClient.getRefineriesYields()).thenReturn(List.of(good, orphan, skipped));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(material));
    when(terminalRepository.findByIdTerminal(42)).thenReturn(Optional.of(terminal));
    when(terminalRepository.findByIdTerminal(999)).thenReturn(Optional.empty());
    when(refineryYieldRepository.findByTerminalIdAndMaterialId(terminalId, materialId))
        .thenReturn(Optional.empty());
    when(refineryYieldRepository.save(any(RefineryYield.class))).thenAnswer(i -> i.getArgument(0));

    service.syncRefineryYields();

    // Exactly one save — only the good row reached the repo
    verify(refineryYieldRepository, times(1)).save(any());
  }

  // ── audit-summary events (REQ-AUDIT-001) ───────────────────────────────

  @Test
  void syncRefiningMethods_recordsAuditSummary() {
    // Given — a mixed batch of exactly one create, one update and one skipped (blank-name) row,
    // so the summary must carry added=1 updated=1.
    UexRefiningMethodDto fresh = new UexRefiningMethodDto(1, "Fresh", "F", 1, 1, 1);
    UexRefiningMethodDto existing = new UexRefiningMethodDto(2, "Existing", "E", 2, 2, 2);
    UexRefiningMethodDto skipped = new UexRefiningMethodDto(3, "", null, 0, 0, 0);

    RefiningMethod existingEntity = new RefiningMethod();
    existingEntity.setId(UUID.randomUUID());
    existingEntity.setName("Existing");

    when(uexClient.getRefineriesMethods()).thenReturn(List.of(fresh, existing, skipped));
    when(refiningMethodRepository.findByName("Fresh")).thenReturn(Optional.empty());
    when(refiningMethodRepository.findByName("Existing")).thenReturn(Optional.of(existingEntity));
    when(refiningMethodRepository.save(any(RefiningMethod.class)))
        .thenAnswer(i -> i.getArgument(0));

    service.syncRefiningMethods();

    // Exactly one system-actor summary event (id/label/target all null), with the per-run counts.
    verify(auditService)
        .record(
            eq(AuditEventType.REFINERY_METHODS_SYNCED),
            isNull(),
            isNull(),
            isNull(),
            argThat(details -> "source=UEX added=1 updated=1".equals(details.toString())));
    verifyNoMoreInteractions(auditService);
  }

  @Test
  void syncRefineryYields_recordsAuditSummary() {
    // Given — a single upsertable yield, so the summary must carry processed=1.
    UUID materialId = UUID.randomUUID();
    UUID terminalId = UUID.randomUUID();
    Material material = new Material();
    material.setId(materialId);
    material.setIdCommodity(1);
    Terminal terminal = new Terminal();
    terminal.setId(terminalId);
    terminal.setIdTerminal(42);

    UexRefineryYieldDto payload = new UexRefineryYieldDto(100, 1, 42, 5);

    when(uexClient.getRefineriesYields()).thenReturn(List.of(payload));
    when(materialRepository.findByIdCommodity(1)).thenReturn(Optional.of(material));
    when(terminalRepository.findByIdTerminal(42)).thenReturn(Optional.of(terminal));
    when(refineryYieldRepository.findByTerminalIdAndMaterialId(terminalId, materialId))
        .thenReturn(Optional.empty());
    when(refineryYieldRepository.save(any(RefineryYield.class))).thenAnswer(i -> i.getArgument(0));

    service.syncRefineryYields();

    verify(auditService)
        .record(
            eq(AuditEventType.REFINERY_YIELDS_SYNCED),
            isNull(),
            isNull(),
            isNull(),
            argThat(details -> "source=UEX processed=1".equals(details.toString())));
    verifyNoMoreInteractions(auditService);
  }

  @Test
  void syncRefiningMethods_emptyResponse_recordsNoAudit() {
    when(uexClient.getRefineriesMethods()).thenReturn(List.of());

    service.syncRefiningMethods();

    // The empty-response early return must never emit a summary event.
    verifyNoInteractions(auditService);
  }

  @Test
  void syncRefineryYields_emptyResponse_recordsNoAudit() {
    when(uexClient.getRefineriesYields()).thenReturn(List.of());

    service.syncRefineryYields();

    verifyNoInteractions(auditService);
  }
}
