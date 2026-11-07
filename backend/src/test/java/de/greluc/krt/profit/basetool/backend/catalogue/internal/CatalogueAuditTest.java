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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.FrequencyType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobTypeDesignationObserver;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ManufacturerMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategoryMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDeletionObserver;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasWriteRequest;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Each catalogue admin mutation records exactly one {@code CATALOGUE} audit event with the row's
 * UUID as subject and its display name as label; an update names only its changed fields
 * (REQ-AUDIT-001, REQ-P4K-046, ADR-0346).
 */
@ExtendWith(MockitoExtension.class)
class CatalogueAuditTest {

  private static final UUID ID = UUID.randomUUID();

  @Mock private AuditRecorder auditRecorder;

  @Mock private CityRepository cityRepository;
  @Mock private CityMapper cityMapper;
  @Mock private OutpostRepository outpostRepository;
  @Mock private OutpostMapper outpostMapper;
  @Mock private PoiRepository poiRepository;
  @Mock private PoiMapper poiMapper;
  @Mock private SpaceStationRepository spaceStationRepository;
  @Mock private TerminalRepository terminalRepository;
  @Mock private TerminalMapper terminalMapper;
  @Mock private ManufacturerRepository manufacturerRepository;
  @Mock private ManufacturerMapper manufacturerMapper;
  @Mock private ShipTypeRepository shipTypeRepository;
  @Mock private ShipTypeMapper shipTypeMapper;
  @Mock private LocationRepository locationRepository;
  @Mock private LocationMapper locationMapper;
  @Mock private MaterialRepository materialRepository;
  @Mock private MaterialPriceRepository materialPriceRepository;
  @Mock private MaterialCategoryRepository materialCategoryRepository;
  @Mock private MaterialMapper materialMapper;
  @Mock private MaterialDeletionObserver materialDeletionObserver;
  @Mock private MaterialCategoryMapper materialCategoryMapper;
  @Mock private MaterialExternalAliasRepository aliasRepository;
  @Mock private StarSystemRepository starSystemRepository;
  @Mock private StarSystemMapper starSystemMapper;
  @Mock private FrequencyTypeRepository frequencyTypeRepository;
  @Mock private FrequencyTypeMapper frequencyTypeMapper;
  @Mock private JobTypeRepository jobTypeRepository;
  @Mock private JobTypeMapper jobTypeMapper;
  @Mock private JobTypeDesignationObserver jobTypeDesignationObserver;
  @Mock private AuthHelperService authHelperService;
  @Mock private ExternalSyncReportRepository externalSyncReportRepository;
  @Mock private MeterRegistry meterRegistry;
  @Mock private AdminDashboardLiveSync adminDashboardLiveSync;

  @InjectMocks private CityService cityService;
  @InjectMocks private OutpostService outpostService;
  @InjectMocks private PoiService poiService;
  @InjectMocks private SpaceStationService spaceStationService;
  @InjectMocks private TerminalService terminalService;
  @InjectMocks private ManufacturerService manufacturerService;
  @InjectMocks private ShipTypeService shipTypeService;
  @InjectMocks private MaterialService materialService;
  @InjectMocks private StarSystemService starSystemService;
  @InjectMocks private FrequencyTypeService frequencyTypeService;
  @InjectMocks private JobTypeService jobTypeService;
  @InjectMocks private SyncReportService syncReportService;

  /**
   * Verifies the one recorded event.
   *
   * @param type the expected type
   * @param label the expected subject label
   * @param details the expected details payload
   */
  private void verifyEvent(AuditEventType type, String label, String details) {
    verify(auditRecorder)
        .record(
            eq(type),
            eq(ID),
            eq(label),
            isNull(),
            argThat(actual -> actual != null && details.equals(actual.toString())));
  }

  @Test
  void cityLoadingDockOverride_setAndCleared_areEachOneEvent() {
    City city = new City();
    city.setId(ID);
    city.setName("Lorville");
    when(cityRepository.findById(ID)).thenReturn(Optional.of(city));
    when(cityRepository.save(city)).thenReturn(city);

    cityService.setLoadingDockOverride(ID, true);
    cityService.clearLoadingDockOverride(ID);

    verifyEvent(
        AuditEventType.CATALOGUE_CITY_LOADING_DOCK_OVERRIDDEN, "Lorville", "hasLoadingDock=true");
    verify(auditRecorder)
        .record(
            AuditEventType.CATALOGUE_CITY_LOADING_DOCK_OVERRIDE_CLEARED,
            ID,
            "Lorville",
            null,
            null);
  }

  @Test
  void outpostPoiAndStationLoadingDockOverrides_recordTheirOwnKind() {
    Outpost outpost = new Outpost();
    outpost.setId(ID);
    outpost.setName("Shubin SAL-2");
    when(outpostRepository.findById(ID)).thenReturn(Optional.of(outpost));
    when(outpostRepository.save(outpost)).thenReturn(outpost);
    Poi poi = new Poi();
    poi.setId(ID);
    poi.setName("Jumptown");
    when(poiRepository.findById(ID)).thenReturn(Optional.of(poi));
    when(poiRepository.save(poi)).thenReturn(poi);
    SpaceStation station = new SpaceStation();
    station.setId(ID);
    station.setName("Everus Harbor");
    when(spaceStationRepository.findById(ID)).thenReturn(Optional.of(station));
    when(spaceStationRepository.save(station)).thenReturn(station);

    outpostService.setLoadingDockOverride(ID, false);
    poiService.clearLoadingDockOverride(ID);
    spaceStationService.setLoadingDockOverride(ID, true);

    verifyEvent(
        AuditEventType.CATALOGUE_OUTPOST_LOADING_DOCK_OVERRIDDEN,
        "Shubin SAL-2",
        "hasLoadingDock=false");
    verify(auditRecorder)
        .record(
            AuditEventType.CATALOGUE_POI_LOADING_DOCK_OVERRIDE_CLEARED, ID, "Jumptown", null, null);
    verifyEvent(
        AuditEventType.CATALOGUE_SPACE_STATION_LOADING_DOCK_OVERRIDDEN,
        "Everus Harbor",
        "hasLoadingDock=true");
  }

  @Test
  void terminalVisibilityAndOverrides_recordOneEventEach() {
    Terminal terminal = new Terminal();
    terminal.setId(ID);
    terminal.setName("TDD Area 18");
    when(terminalRepository.findById(ID)).thenReturn(Optional.of(terminal));
    when(terminalRepository.save(terminal)).thenReturn(terminal);

    terminalService.updateTerminalVisibility(ID, true);
    terminalService.setLoadingDockOverride(ID, true);
    terminalService.clearLoadingDockOverride(ID);
    terminalService.setAutoLoadOverride(ID, false);
    terminalService.clearAutoLoadOverride(ID);

    verifyEvent(AuditEventType.CATALOGUE_TERMINAL_VISIBILITY_CHANGED, "TDD Area 18", "hidden=true");
    verifyEvent(
        AuditEventType.CATALOGUE_TERMINAL_LOADING_DOCK_OVERRIDDEN,
        "TDD Area 18",
        "hasLoadingDock=true");
    verify(auditRecorder)
        .record(
            AuditEventType.CATALOGUE_TERMINAL_LOADING_DOCK_OVERRIDE_CLEARED,
            ID,
            "TDD Area 18",
            null,
            null);
    verifyEvent(
        AuditEventType.CATALOGUE_TERMINAL_AUTO_LOAD_OVERRIDDEN, "TDD Area 18", "autoLoad=false");
    verify(auditRecorder)
        .record(
            AuditEventType.CATALOGUE_TERMINAL_AUTO_LOAD_OVERRIDE_CLEARED,
            ID,
            "TDD Area 18",
            null,
            null);
  }

  @Test
  void manufacturerAndShipTypeVisibility_recordTheSwitch() {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setId(ID);
    manufacturer.setName("Drake Interplanetary");
    when(manufacturerRepository.findById(ID)).thenReturn(Optional.of(manufacturer));
    when(manufacturerRepository.save(manufacturer)).thenReturn(manufacturer);
    ShipType shipType = new ShipType();
    shipType.setId(ID);
    shipType.setName("Cutlass Black");
    when(shipTypeRepository.findById(ID)).thenReturn(Optional.of(shipType));
    when(shipTypeRepository.save(shipType)).thenReturn(shipType);

    manufacturerService.updateManufacturerVisibility(ID, true);
    shipTypeService.updateShipTypeVisibility(ID, false);

    verifyEvent(
        AuditEventType.CATALOGUE_MANUFACTURER_VISIBILITY_CHANGED,
        "Drake Interplanetary",
        "hidden=true");
    verifyEvent(
        AuditEventType.CATALOGUE_SHIP_TYPE_VISIBILITY_CHANGED, "Cutlass Black", "hidden=false");
  }

  @Test
  void location_createUpdateDelete_updateNamesChangedFieldsOnly() {
    LocationService locationService =
        new LocationService(locationRepository, locationMapper, List.of(), auditRecorder);
    Location location = new Location();
    location.setId(ID);
    location.setName("Home");
    location.setDescription("old text");
    location.setVersion(0L);
    when(locationRepository.save(location)).thenReturn(location);
    when(locationRepository.findById(ID)).thenReturn(Optional.of(location));

    locationService.createLocation(location);
    locationService.updateLocation(ID, new LocationDto(ID, "Home", "new text", true, false, 0L));
    locationService.deleteLocation(ID);

    verifyEvent(AuditEventType.CATALOGUE_LOCATION_CREATED, "Home", "hidden=false");
    verifyEvent(AuditEventType.CATALOGUE_LOCATION_UPDATED, "Home", "changed=description,hidden");
    verify(auditRecorder).record(AuditEventType.CATALOGUE_LOCATION_DELETED, ID, "Home", null, null);
  }

  @Test
  void location_updateWithoutChange_recordsNothing() {
    LocationService locationService =
        new LocationService(locationRepository, locationMapper, List.of(), auditRecorder);
    Location location = new Location();
    location.setId(ID);
    location.setName("Home");
    location.setVersion(0L);
    when(locationRepository.findById(ID)).thenReturn(Optional.of(location));
    when(locationRepository.save(location)).thenReturn(location);

    locationService.updateLocation(ID, new LocationDto(ID, "Home", null, false, false, 0L));

    verifyNoInteractions(auditRecorder);
  }

  @Test
  void material_createUpdateDelete_recordEnumsAndChangedFieldsOnly() {
    when(materialRepository.save(any(Material.class)))
        .thenAnswer(invocation -> {
          Material saved = invocation.getArgument(0);
          saved.setId(ID);
          return saved;
        });
    materialService.createMaterial(
        new MaterialCreateDto(
            "Quantanium", "RAW", "SCU", "secret note", null, null, null, null, null, null, null));
    verifyEvent(
        AuditEventType.CATALOGUE_MATERIAL_CREATED, "Quantanium", "type=RAW quantityType=SCU");

    Material stored = new Material();
    stored.setId(ID);
    stored.setName("Quantanium");
    stored.setType(MaterialType.RAW);
    stored.setQuantityType(QuantityType.SCU);
    stored.setDescription("old");
    when(materialRepository.findById(ID)).thenReturn(Optional.of(stored));
    Material incoming = new Material();
    incoming.setName("Quantainium");
    incoming.setType(MaterialType.RAW);
    incoming.setQuantityType(QuantityType.SCU);
    incoming.setDescription("old");
    materialService.updateMaterial(ID, incoming);
    verifyEvent(AuditEventType.CATALOGUE_MATERIAL_UPDATED, "Quantainium", "changed=name");

    materialService.deleteMaterial(ID);
    verify(auditRecorder)
        .record(AuditEventType.CATALOGUE_MATERIAL_DELETED, ID, "Quantainium", null, null);
  }

  @Test
  void materialAlias_createUpdateDelete_carrySourceNotFreeText() {
    MaterialExternalAliasService aliasService =
        new MaterialExternalAliasService(
            aliasRepository, materialRepository, authHelperService, auditRecorder);
    Material material = new Material();
    material.setId(UUID.randomUUID());
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    when(aliasRepository.save(any(MaterialExternalAlias.class)))
        .thenAnswer(invocation -> {
          MaterialExternalAlias saved = invocation.getArgument(0);
          saved.setId(ID);
          return saved;
        });
    aliasService.create(
        new MaterialExternalAliasWriteRequest(
            material.getId(), "UEX", "Quanta", null, null, null, "private note", null));
    verifyEvent(AuditEventType.CATALOGUE_MATERIAL_ALIAS_CREATED, "Quanta", "source=UEX");

    MaterialExternalAlias stored = new MaterialExternalAlias();
    stored.setId(ID);
    stored.setMaterial(material);
    stored.setSourceSystem(MaterialExternalAliasSource.UEX);
    stored.setExternalName("Quanta");
    stored.setVersion(0L);
    when(aliasRepository.findById(ID)).thenReturn(Optional.of(stored));
    when(aliasRepository.saveAndFlush(stored)).thenReturn(stored);
    aliasService.update(
        ID,
        new MaterialExternalAliasWriteRequest(
            material.getId(), "UEX", "Quanta", null, null, null, "edited note", 0L));
    verifyEvent(AuditEventType.CATALOGUE_MATERIAL_ALIAS_UPDATED, "Quanta", "changed=note");

    aliasService.delete(ID);
    verifyEvent(AuditEventType.CATALOGUE_MATERIAL_ALIAS_DELETED, "Quanta", "source=UEX");
  }

  @Test
  void starSystem_createUpdateDelete() {
    StarSystem system = new StarSystem();
    system.setId(ID);
    system.setName("Pyro");
    when(starSystemRepository.save(system)).thenReturn(system);
    when(starSystemRepository.findById(ID)).thenReturn(Optional.of(system));
    StarSystem edited = new StarSystem();
    edited.setName("Pyro");
    edited.setDescription("lawless");

    starSystemService.createStarSystem(system);
    starSystemService.updateStarSystem(ID, edited);
    starSystemService.deleteStarSystem(ID);

    verify(auditRecorder)
        .record(AuditEventType.CATALOGUE_STAR_SYSTEM_CREATED, ID, "Pyro", null, null);
    verifyEvent(AuditEventType.CATALOGUE_STAR_SYSTEM_UPDATED, "Pyro", "changed=description");
    verify(auditRecorder)
        .record(AuditEventType.CATALOGUE_STAR_SYSTEM_DELETED, ID, "Pyro", null, null);
  }

  @Test
  void frequencyType_lifecycleAndReorder() {
    FrequencyType type = new FrequencyType();
    type.setId(ID);
    type.setName("Weekly");
    type.setActive(true);
    when(frequencyTypeRepository.save(type)).thenReturn(type);
    when(frequencyTypeRepository.findById(ID)).thenReturn(Optional.of(type));
    when(frequencyTypeRepository.existsById(ID)).thenReturn(true);
    FrequencyType edited = new FrequencyType();
    edited.setName("Weekly");
    edited.setActive(false);

    frequencyTypeService.createFrequencyType(type);
    frequencyTypeService.updateFrequencyType(ID, edited);
    frequencyTypeService.activateFrequencyType(ID);
    frequencyTypeService.deleteFrequencyType(ID);
    frequencyTypeService.reorderFrequencyTypes(List.of(ID));

    verify(auditRecorder)
        .record(AuditEventType.CATALOGUE_FREQUENCY_TYPE_CREATED, ID, "Weekly", null, null);
    verifyEvent(AuditEventType.CATALOGUE_FREQUENCY_TYPE_UPDATED, "Weekly", "changed=active");
    verify(auditRecorder)
        .record(AuditEventType.CATALOGUE_FREQUENCY_TYPE_ACTIVATED, ID, "Weekly", null, null);
    verify(auditRecorder)
        .record(AuditEventType.CATALOGUE_FREQUENCY_TYPE_DEACTIVATED, ID, "Weekly", null, null);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.CATALOGUE_FREQUENCY_TYPES_REORDERED),
            isNull(),
            isNull(),
            isNull(),
            argThat(actual -> actual != null && "count=1".equals(actual.toString())));
  }

  @Test
  void jobType_lifecycle() {
    JobType type = new JobType();
    type.setId(ID);
    type.setName("Pilot");
    type.setArchetype(JobTypeArchetype.CREW);
    type.setVersion(0L);
    when(jobTypeRepository.existsByNameIgnoreCase("Pilot")).thenReturn(false);
    when(jobTypeRepository.save(type)).thenReturn(type);
    when(jobTypeRepository.findById(ID)).thenReturn(Optional.of(type));

    jobTypeService.createJobType(type);
    jobTypeService.updateJobType(
        ID,
        new JobTypeDto(ID, "Pilot", "flies", JobTypeArchetype.CREW, null, true, false, false, 0L));
    jobTypeService.deleteJobType(ID);
    jobTypeService.activateJobType(ID);

    verifyEvent(AuditEventType.CATALOGUE_JOB_TYPE_CREATED, "Pilot", "archetype=CREW");
    verifyEvent(AuditEventType.CATALOGUE_JOB_TYPE_UPDATED, "Pilot", "changed=description");
    verify(auditRecorder)
        .record(AuditEventType.CATALOGUE_JOB_TYPE_DEACTIVATED, ID, "Pilot", null, null);
    verify(auditRecorder)
        .record(AuditEventType.CATALOGUE_JOB_TYPE_ACTIVATED, ID, "Pilot", null, null);
    assertThat(type.isActive()).isTrue();
  }

  @Test
  void syncReportManualDelete_recordsSourceAgeAndCount() {
    when(externalSyncReportRepository.deleteBySourceSystemAndRanAtBefore(
            eq(SyncSourceSystem.UEX), any()))
        .thenReturn(4);
    when(externalSyncReportRepository.deleteByRanAtBefore(any())).thenReturn(0);

    syncReportService.deleteOlderThan(SyncSourceSystem.UEX, 30);
    syncReportService.deleteOlderThan(null, 7);

    verify(auditRecorder)
        .record(
            eq(AuditEventType.CATALOGUE_SYNC_REPORTS_DELETED),
            isNull(),
            isNull(),
            isNull(),
            argThat(
                d -> d != null && "source=UEX olderThanDays=30 deleted=4".equals(d.toString())));
    verify(auditRecorder)
        .record(
            eq(AuditEventType.CATALOGUE_SYNC_REPORTS_DELETED),
            isNull(),
            isNull(),
            isNull(),
            argThat(d -> d != null && "source=ALL olderThanDays=7 deleted=0".equals(d.toString())));
  }
}
