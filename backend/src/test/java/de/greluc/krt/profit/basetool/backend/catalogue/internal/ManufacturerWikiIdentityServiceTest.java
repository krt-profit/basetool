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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** The admin primary choice between a manufacturer's SC Wiki entries (ADR-0361). */
@ExtendWith(MockitoExtension.class)
class ManufacturerWikiIdentityServiceTest {

  private static final UUID BANU_ID = UUID.randomUUID();
  private static final UUID BANU = UUID.randomUUID();
  private static final UUID BANU_SOULI = UUID.randomUUID();

  @Mock private ManufacturerRepository manufacturerRepository;
  @Mock private ManufacturerScwikiIdentityRepository identityRepository;
  @Mock private AuditRecorder auditRecorder;
  @Mock private AdminDashboardLiveSync adminDashboardLiveSync;

  private ManufacturerWikiIdentityService service;
  private Manufacturer banu;

  @BeforeEach
  void setUp() {
    service =
        new ManufacturerWikiIdentityService(
            manufacturerRepository, identityRepository, auditRecorder, adminDashboardLiveSync);
    banu = new Manufacturer();
    banu.setId(BANU_ID);
    banu.setName("Banu");
    banu.setAbbreviation("BANU");
    banu.setVersion(3L);
    banu.setScwikiUuid(BANU);
    banu.setScwikiCode("BANU");
  }

  @Test
  void listsEachDuplicatedManufacturerOnceWithItsPrimaryMarked() {
    when(identityRepository.findDuplicated())
        .thenReturn(
            List.of(identity(BANU, "Banu", "BANU"), identity(BANU_SOULI, "Banu Souli", null)));

    List<ManufacturerWikiDuplicateDto> duplicates = service.listDuplicates();

    assertThat(duplicates).hasSize(1);
    ManufacturerWikiDuplicateDto dto = duplicates.getFirst();
    assertThat(dto.manufacturerId()).isEqualTo(BANU_ID);
    assertThat(dto.version()).isEqualTo(3L);
    assertThat(dto.identities())
        .extracting(ManufacturerWikiIdentityDto::scwikiUuid, ManufacturerWikiIdentityDto::primary)
        .containsExactly(tuple(BANU, true), tuple(BANU_SOULI, false));
  }

  @Test
  void choosingAnAliasMakesItPrimaryAuditsAndAnnounces() {
    ManufacturerScwikiIdentity souli = identity(BANU_SOULI, "Banu Souli", "BNSL");
    when(manufacturerRepository.findById(BANU_ID)).thenReturn(Optional.of(banu));
    when(identityRepository.findById(BANU_SOULI)).thenReturn(Optional.of(souli));
    when(manufacturerRepository.saveAndFlush(banu)).thenReturn(banu);
    when(identityRepository.findOfManufacturer(BANU_ID))
        .thenReturn(List.of(identity(BANU, "Banu", "BANU"), souli));

    ManufacturerWikiDuplicateDto result =
        service.choosePrimary(BANU_ID, new ManufacturerWikiPrimaryRequest(BANU_SOULI, 3L));

    assertThat(banu.getScwikiUuid()).isEqualTo(BANU_SOULI);
    assertThat(banu.getScwikiCode()).isEqualTo("BNSL");
    assertThat(result.identities())
        .filteredOn(ManufacturerWikiIdentityDto::primary)
        .extracting(ManufacturerWikiIdentityDto::scwikiUuid)
        .containsExactly(BANU_SOULI);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.CATALOGUE_MANUFACTURER_WIKI_PRIMARY_CHANGED),
            eq(BANU_ID),
            eq("Banu"),
            isNull(),
            argThat(details -> ("from=" + BANU + " to=" + BANU_SOULI).contentEquals(details)));
    verify(adminDashboardLiveSync).syncStatusChanged();
  }

  @Test
  void choosingTheCurrentPrimaryRecordsNothing() {
    when(manufacturerRepository.findById(BANU_ID)).thenReturn(Optional.of(banu));
    when(identityRepository.findById(BANU)).thenReturn(Optional.of(identity(BANU, "Banu", "BANU")));

    service.choosePrimary(BANU_ID, new ManufacturerWikiPrimaryRequest(BANU, 3L));

    verify(manufacturerRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditRecorder, adminDashboardLiveSync);
  }

  @Test
  void aStaleVersionIsAConflict() {
    when(manufacturerRepository.findById(BANU_ID)).thenReturn(Optional.of(banu));

    assertThatThrownBy(() ->
            service.choosePrimary(BANU_ID, new ManufacturerWikiPrimaryRequest(BANU_SOULI, 2L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verifyNoInteractions(auditRecorder);
  }

  @Test
  void anEntryOfAnotherManufacturerIsRejected() {
    Manufacturer other = new Manufacturer();
    other.setId(UUID.randomUUID());
    ManufacturerScwikiIdentity foreign =
        new ManufacturerScwikiIdentity(BANU_SOULI, other, "Banu Souli", null, Instant.EPOCH);
    when(manufacturerRepository.findById(BANU_ID)).thenReturn(Optional.of(banu));
    when(identityRepository.findById(BANU_SOULI)).thenReturn(Optional.of(foreign));

    assertThatThrownBy(() ->
            service.choosePrimary(BANU_ID, new ManufacturerWikiPrimaryRequest(BANU_SOULI, 3L)))
        .isInstanceOf(BadRequestException.class);
    assertThat(banu.getScwikiUuid()).isEqualTo(BANU);
    verifyNoInteractions(auditRecorder);
  }

  private ManufacturerScwikiIdentity identity(UUID uuid, String name, String code) {
    return new ManufacturerScwikiIdentity(uuid, banu, name, code, Instant.EPOCH);
  }
}
