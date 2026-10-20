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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipProductionStatus;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure Mockito unit tests for {@link HangarImportService}. The contract under test:
 *
 * <ul>
 *   <li>Exact case-insensitive matches are imported.
 *   <li>Hyphen/whitespace drift between the Fleetview export and the canonical UEX ship-type name
 *       is absorbed by the normalised fallback (e.g. {@code "L21 Wolf"} matches {@code "L-21
 *       Wolf"}).
 *   <li>Unmatched entries surface in {@code skippedShips} with their original casing, deduplicated
 *       case-insensitively.
 *   <li>Each upload entry is matched to one hangar ship of its type, by pledge id first; only the
 *       entries left over create ships, so a re-import creates nothing.
 *   <li>A matched ship takes the entry's production status and pledge data (REQ-HANGAR-015).
 *   <li>Surplus ships already in the hangar are never deleted.
 *   <li>Empty file / unparseable JSON / unknown user surface as the expected exceptions for the
 *       controller's HTTP-status mapping.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class HangarImportServiceTest {

  @Mock private ShipRepository shipRepository;
  @Mock private ShipTypeRepository shipTypeRepository;
  @Mock private UserRepository userRepository;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuditService auditService;
  @Mock private HangarUnitLiveSync unitLiveSync;

  private HangarImportService hangarImportService;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @BeforeEach
  void setUp() {
    hangarImportService =
        new HangarImportService(
            shipRepository,
            shipTypeRepository,
            userRepository,
            objectMapper,
            ownerScopeService,
            auditService,
            unitLiveSync);
    de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron stubSquadron =
        new de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron();
    stubSquadron.setId(UUID.randomUUID());
    org.mockito.Mockito.lenient()
        .when(ownerScopeService.resolveOrgUnitForImport(any()))
        .thenReturn(stubSquadron);
  }

  @Test
  void importFleetview_allMatched_importsAllShips() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type135c = shipTypeWithName("135c");
    ShipType typeZeus = shipTypeWithName("zeus mk ii mr");

    String json =
        """
        [
          {"name":"135c","shipname":"","type":"ship"},
          {"name":"zeus mk ii mr","shipname":"My Zeus","type":"ship"}
        ]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type135c, typeZeus));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.skippedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(0);
    assertThat(result.skippedShips()).isEmpty();
    assertThat(result.duplicateShips()).isEmpty();
    verify(shipRepository, times(2)).save(any(Ship.class));
    verify(auditService)
        .record(
            eq(AuditEventType.HANGAR_IMPORTED),
            isNull(),
            isNull(),
            eq(userId),
            argThat(d -> d.toString().contains("created=2")));
  }

  @Test
  void importFleetview_partialMatch_skipsUnknownShips() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type135c = shipTypeWithName("135c");

    String json =
        """
        [
          {"name":"135c","shipname":"","type":"ship"},
          {"name":"unknown alien ship","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type135c));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("unknown alien ship");
    verify(shipRepository, times(1)).save(any(Ship.class));
  }

  @Test
  void importFleetview_triplicateInJson_hangarEmpty_createsAllThree() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("aurora mr");

    String json =
        """
        [
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(3);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(shipRepository, times(3)).save(any(Ship.class));
  }

  @Test
  void importFleetview_triplicateInJson_hangarHasOne_createsTwoMore() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("aurora mr");

    String json =
        """
        [
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.lockAllOwnedByOrderById(userId)).thenReturn(owned(type, 1));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.duplicateCount()).isEqualTo(1);
    verify(shipRepository, times(2)).save(any(Ship.class));
  }

  @Test
  void importFleetview_triplicateInJson_hangarAlreadyHasThree_createsNone() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("aurora mr");

    String json =
        """
        [
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.lockAllOwnedByOrderById(userId)).thenReturn(owned(type, 3));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(3);
    verify(shipRepository, never()).save(any(Ship.class));
    verifyNoInteractions(auditService);
  }

  @Test
  void importFleetview_triplicateInJson_hangarHasFive_createsNoneAndDoesNotDelete() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("aurora mr");

    String json =
        """
        [
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.lockAllOwnedByOrderById(userId)).thenReturn(owned(type, 5));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(3);
    verify(shipRepository, never()).save(any(Ship.class));
    verify(shipRepository, never()).delete(any());
    verify(shipRepository, never()).deleteAll(any());
  }

  @Test
  void importFleetview_mixed_partialCreation() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType typeA = shipTypeWithName("vulture");
    ShipType typeB = shipTypeWithName("aurora mr");

    String json =
        """
        [
          {"name":"vulture","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"},
          {"name":"aurora mr","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(typeA, typeB));
    when(shipRepository.lockAllOwnedByOrderById(userId))
        .thenReturn(concat(owned(typeA, 2), owned(typeB, 1)));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.duplicateCount()).isEqualTo(2);
    verify(shipRepository, times(2)).save(any(Ship.class));
    verify(shipRepository, times(1)).lockAllOwnedByOrderById(userId);
    verify(ownerScopeService, times(1)).resolveOrgUnitForImport(any());
  }

  @Test
  void importFleetview_setsDefaultInsurance() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("vulture");

    String json =
        """
        [{"name":"vulture","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    hangarImportService.importShips(userId, file);

    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getInsurance()).isEqualTo(FleetExportParser.DEFAULT_INSURANCE);
  }

  @Test
  void importFleetview_setsIndividualShipName_whenPresent() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("890 jump");

    String json =
        """
        [{"name":"890 jump","shipname":"Stella Aeterna","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    hangarImportService.importShips(userId, file);

    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Stella Aeterna");
  }

  @Test
  void importFleetview_caseOnlyDifference_stillMatches() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("atls");

    String json =
        """
        [{"name":"ATLS","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  @Test
  void importFleetview_hyphenInDbNotInJson_stillMatchesViaNormalisation() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType wolf = shipTypeWithName("L-21 Wolf");

    String json =
        """
        [{"name":"L21 Wolf","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(wolf));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  @Test
  void importFleetview_hyphenInJsonNotInDb_stillMatchesViaNormalisation() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType wolf = shipTypeWithName("L21 Wolf");

    String json =
        """
        [{"name":"L-21 Wolf","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(wolf));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  @Test
  void importFleetview_twoSpellingsSameShip_aggregateToSameType() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType wolf = shipTypeWithName("L-21 Wolf");

    String json =
        """
        [
          {"name":"L-21 Wolf","shipname":"","type":"ship"},
          {"name":"L21 Wolf","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(wolf));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.skippedCount()).isEqualTo(0);
    verify(shipRepository, times(2)).save(any(Ship.class));
  }

  @Test
  void importFleetview_extraWhitespaceInJson_stillMatches() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType cyclone = shipTypeWithName("Cyclone-AA");

    String json =
        """
        [{"name":"  cyclone   aa  ","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(cyclone));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  @Test
  void importFleetview_skippedList_preservesOriginalCasingAndDedupes() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    String json =
        """
        [
          {"name":"Fictional Ship","shipname":"","type":"ship"},
          {"name":"fictional ship","shipname":"","type":"ship"},
          {"name":"Another Unknown","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of());

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(2);
    assertThat(result.skippedShips()).containsExactly("Fictional Ship", "Another Unknown");
    verify(shipRepository, never()).save(any(Ship.class));
  }

  @Test
  void importFleetview_shipTypeWithBlankName_isIgnoredInIndex() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType blank = shipTypeWithName("");
    ShipType real = shipTypeWithName("135c");

    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(blank, real));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  @Test
  void importFleetview_fvAbbreviatesUexCanonicalSuffix_resolvesUniquely() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType hercules = shipTypeWithName("A2 Hercules Starlifter");
    ShipType c2Hercules = shipTypeWithName("C2 Hercules Starlifter");

    String json =
        """
        [{"name":"A2 Hercules","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(hercules, c2Hercules));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(hercules);
  }

  @Test
  void importFleetview_fvAbbreviatesAuroraVariant_resolvesToMkI() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType mkICl = shipTypeWithName("Aurora Mk I CL");
    ShipType mkIEs = shipTypeWithName("Aurora Mk I ES");
    ShipType mkILn = shipTypeWithName("Aurora Mk I LN");
    ShipType mkILx = shipTypeWithName("Aurora Mk I LX");
    ShipType mkIMr = shipTypeWithName("Aurora Mk I MR");
    ShipType mkIIPlain = shipTypeWithName("Aurora Mk II");

    String json =
        """
        [{"name":"Aurora MR","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll())
        .thenReturn(List.of(mkICl, mkIEs, mkILn, mkILx, mkIMr, mkIIPlain));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(mkIMr);
  }

  @Test
  void importFleetview_tokenReorderingMatches() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType plain = shipTypeWithName("Gladius");
    ShipType pirate = shipTypeWithName("Gladius Pirate");
    ShipType valiant = shipTypeWithName("Gladius Valiant");

    String json =
        """
        [{"name":"Pirate Gladius","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(plain, pirate, valiant));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(pirate);
  }

  @Test
  void importFleetview_uexShorterThanFv_resolvesViaStage4() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType ursa = shipTypeWithName("Ursa");
    ShipType ursaFortuna = shipTypeWithName("Ursa Fortuna");
    ShipType ursaMedivac = shipTypeWithName("Ursa Medivac");

    String json =
        """
        [{"name":"Ursa Rover","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(ursa, ursaFortuna, ursaMedivac));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(ursa);
  }

  @Test
  void importFleetview_stage3Ambiguous_skipsInsteadOfGuessing() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType mkI = shipTypeWithName("F7C-M Super Hornet Mk I");
    ShipType heartseeker = shipTypeWithName("F7C-M Super Hornet Heartseeker Mk I");
    ShipType mkII = shipTypeWithName("F7C-M Super Hornet Mk II");

    String json =
        """
        [{"name":"F7C-M Super Hornet","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(mkI, heartseeker, mkII));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("F7C-M Super Hornet");
    verify(shipRepository, never()).save(any(Ship.class));
  }

  @Test
  void importFleetview_stage4Ambiguous_skipsInsteadOfGuessing() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType foo = shipTypeWithName("Foo");
    ShipType bar = shipTypeWithName("Bar");

    String json =
        """
        [{"name":"Foo Bar","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(foo, bar));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("Foo Bar");
    verify(shipRepository, never()).save(any(Ship.class));
  }

  @Test
  void importFleetview_exactMatchWinsOverTokenSubset() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType plain = shipTypeWithName("Gladius");
    ShipType pirate = shipTypeWithName("Gladius Pirate");
    ShipType valiant = shipTypeWithName("Gladius Valiant");

    String json =
        """
        [{"name":"Gladius","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(plain, pirate, valiant));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(plain);
  }

  @Test
  void importFleetview_emptyFile_throws400() {
    UUID userId = UUID.randomUUID();
    MockMultipartFile emptyFile =
        new MockMultipartFile("file", "fleetview.json", "application/json", new byte[0]);

    assertThrows(
        BadRequestException.class, () -> hangarImportService.importShips(userId, emptyFile));
  }

  @Test
  void importFleetview_invalidJson_throws400() {
    UUID userId = UUID.randomUUID();
    MockMultipartFile file = multipartFile("THIS IS NOT JSON");

    assertThrows(BadRequestException.class, () -> hangarImportService.importShips(userId, file));
  }

  @Test
  void importFleetview_unknownUser_throws404() {
    UUID userId = UUID.randomUUID();
    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> hangarImportService.importShips(userId, file));
  }

  @Test
  void importShips_shiplistFormat_isAutoDetectedAndImported() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType polaris = shipTypeWithName("Polaris");

    String shiplistJson =
        """
        [{
          "ship_code":        "RSI_Polaris",
          "ship_name":        "Polaris",
          "manufacturer_code":"RSI",
          "manufacturer_name":"Roberts Space Industries",
          "lti":              true,
          "name":             "Polaris",
          "warbond":          false,
          "entity_type":      "ship",
          "pledge_id":        "44477114",
          "pledge_name":      "Standalone Ship - STV",
          "pledge_date":      "November 03, 2022",
          "pledge_cost":      "$720.00 USD"
        }]
        """;
    MockMultipartFile file = multipartFile(shiplistJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(polaris));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    verify(shipRepository).save(any(Ship.class));
  }

  @Test
  void importShips_shiplistLtiTrue_setsLtiInsurance() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("Vulture");

    String shiplistJson =
        """
        [{
          "ship_code": "DRAK_Vulture",
          "ship_name": "Vulture",
          "lti":       true,
          "name":      "Vulture",
          "entity_type":"ship",
          "pledge_id": "12345"
        }]
        """;
    MockMultipartFile file = multipartFile(shiplistJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    hangarImportService.importShips(userId, file);

    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getInsurance()).isEqualTo(FleetExportParser.LTI_INSURANCE);
  }

  @Test
  void importShips_shiplistLtiFalse_fallsBackToDefaultInsurance() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("Avenger Stalker");

    String shiplistJson =
        """
        [{
          "ship_code": "AEGS_Avenger_Stalker",
          "ship_name": "Avenger Stalker",
          "lti":       false,
          "name":      "Avenger Stalker",
          "entity_type":"ship",
          "pledge_id": "18705924"
        }]
        """;
    MockMultipartFile file = multipartFile(shiplistJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    hangarImportService.importShips(userId, file);

    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getInsurance()).isEqualTo(FleetExportParser.DEFAULT_INSURANCE);
  }

  @Test
  void importShips_shiplistCustomShipName_isSetAsIndividualName() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("600i Explorer");

    String shiplistJson =
        """
        [{
          "ship_code": "ORIG_600i",
          "ship_name": "KRT Olymp",
          "lti":       true,
          "name":      "600i Explorer",
          "entity_type":"ship",
          "pledge_id": "29528209"
        }]
        """;
    MockMultipartFile file = multipartFile(shiplistJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    hangarImportService.importShips(userId, file);

    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("KRT Olymp");
  }

  @Test
  void importShips_shiplistShipNameEchoesModelName_individualNameStaysNull() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("325a");

    String shiplistJson =
        """
        [{
          "ship_code": "ORIG_325a",
          "ship_name": "325a",
          "lti":       false,
          "name":      "325a Fighter",
          "entity_type":"ship",
          "pledge_id": "18896001"
        }]
        """;
    MockMultipartFile file = multipartFile(shiplistJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    hangarImportService.importShips(userId, file);

    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isNull();
    assertThat(captor.getValue().getShipType()).isSameAs(type);
  }

  @Test
  void importShips_shiplistNonShipEntityType_isDroppedAtParseStep() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("Polaris");

    String shiplistJson =
        """
        [
          {
            "ship_code": "RSI_Polaris",
            "ship_name": "Polaris",
            "lti":       true,
            "name":      "Polaris",
            "entity_type":"ship",
            "pledge_id": "111"
          },
          {
            "ship_code": "PAINT_FOO",
            "ship_name": "Foo Paint",
            "lti":       false,
            "name":      "Foo Paint",
            "entity_type":"paint",
            "pledge_id": "222"
          }
        ]
        """;
    MockMultipartFile file = multipartFile(shiplistJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    assertThat(result.skippedShips()).isEmpty();
    verify(shipRepository, times(1)).save(any(Ship.class));
  }

  @Test
  void importShips_fleetviewFormatStillRecognisedAfterShiplistAddition() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("135c");

    String fleetviewJson =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(fleetviewJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
  }

  @Test
  void importShips_unknownFormat_throws400() {
    UUID userId = UUID.randomUUID();
    String mysteryJson =
        """
        [{"foo":"bar","baz":42}]
        """;
    MockMultipartFile file = multipartFile(mysteryJson);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> hangarImportService.importShips(userId, file));
    assertThat(ex.getMessage()).contains("Unknown ship-list format");
  }

  @Test
  void importShips_rootIsObjectNotArray_throws400() {
    UUID userId = UUID.randomUUID();
    MockMultipartFile file =
        multipartFile(
            """
            {"name":"135c","shipname":"","type":"ship"}
            """);

    assertThrows(BadRequestException.class, () -> hangarImportService.importShips(userId, file));
  }

  @Test
  void importShips_emptyArray_returnsAllZero() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    MockMultipartFile file = multipartFile("[]");

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of());

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(shipRepository, never()).save(any(Ship.class));
  }

  @Test
  void importShips_shiplistMixedResolution() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType hercules = shipTypeWithName("A2 Hercules Starlifter");
    ShipType ursa = shipTypeWithName("Ursa");
    ShipType polaris = shipTypeWithName("Polaris");

    String shiplistJson =
        """
        [
          { "ship_code":"RSI_Polaris", "ship_name":"Polaris",
            "lti":true, "name":"Polaris", "entity_type":"ship", "pledge_id":"1" },
          { "ship_code":"CRUS_Hercules_Starlifter_A2", "ship_name":"Hercules Starlifter A2",
            "lti":true, "name":"Hercules Starlifter A2", "entity_type":"ship", "pledge_id":"2" },
          { "ship_code":"RSI_Ursa", "ship_name":"Ursa",
            "lti":false, "name":"Ursa Rover", "entity_type":"ship", "pledge_id":"3" },
          { "ship_code":"ORIG_600i", "ship_name":"KRT Olymp",
            "lti":true, "name":"600i Exploration Module", "entity_type":"ship", "pledge_id":"4" }
        ]
        """;
    MockMultipartFile file = multipartFile(shiplistJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(hercules, ursa, polaris));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(3);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("600i Exploration Module");
    verify(shipRepository, times(3)).save(any(Ship.class));
  }

  @Test
  void importShips_starjumpFormat_isAutoDetectedAndShipsImported() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType perseus = shipTypeWithName("Perseus");
    ShipType galaxy = shipTypeWithName("Galaxy");

    String starjumpJson =
        """
        {
          "type": "starjumpFleetviewer",
          "version": 1,
          "canvasItems": [
            { "id":"a", "itemType":"SHIP", "shipSlug":"perseus", "variantSlug":"",
              "defaultText":"Perseus" },
            { "id":"b", "itemType":"TEXTGROUP", "text":"Perseus" },
            { "id":"c", "itemType":"SHIP", "shipSlug":"galaxy", "variantSlug":"",
              "defaultText":"Galaxy" },
            { "id":"d", "itemType":"TEXTGROUP", "text":"Galaxy" }
          ]
        }
        """;
    MockMultipartFile file = multipartFile(starjumpJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(perseus, galaxy));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.skippedCount()).isEqualTo(0);
    verify(shipRepository, times(2)).save(any(Ship.class));
  }

  @Test
  void importShips_starjumpSlugFallback_resolvesViaUexSlugWhenNameMisses() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType zeus = shipTypeWithSlugs("Zeus MK II MR", "zeus-mkii-mr", null);

    String starjumpJson =
        """
        {
          "type": "starjumpFleetviewer",
          "canvasItems": [
            { "itemType":"SHIP", "shipSlug":"zeus-mkii-mr", "variantSlug":"",
              "defaultText":"Totally Unrelated Display Label" }
          ]
        }
        """;
    MockMultipartFile file = multipartFile(starjumpJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(zeus));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(zeus);
  }

  @Test
  void importShips_starjumpSlugFallback_resolvesViaScwikiSlugWhenNameAndUexMiss() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType ship = shipTypeWithSlugs("Canonical Name", null, "orig-100i");

    String starjumpJson =
        """
        {
          "type": "starjumpFleetviewer",
          "canvasItems": [
            { "itemType":"SHIP", "shipSlug":"orig-100i", "defaultText":"Unmatched Label" }
          ]
        }
        """;
    MockMultipartFile file = multipartFile(starjumpJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(ship));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(ship);
  }

  @Test
  void importShips_starjumpNameMatchWinsOverSlugMatch() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType perseus = shipTypeWithName("Perseus");
    ShipType decoy = shipTypeWithSlugs("Decoy Ship", "decoy-slug", null);

    String starjumpJson =
        """
        {
          "type": "starjumpFleetviewer",
          "canvasItems": [
            { "itemType":"SHIP", "shipSlug":"decoy-slug", "defaultText":"Perseus" }
          ]
        }
        """;
    MockMultipartFile file = multipartFile(starjumpJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(perseus, decoy));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(perseus);
  }

  @Test
  void importShips_starjumpUnmatchedShip_isSkippedUnderDefaultText() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    String starjumpJson =
        """
        {
          "type": "starjumpFleetviewer",
          "canvasItems": [
            { "itemType":"SHIP", "shipSlug":"alien-xyz", "defaultText":"Alien Mystery Ship" }
          ]
        }
        """;
    MockMultipartFile file = multipartFile(starjumpJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of());

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("Alien Mystery Ship");
    verify(shipRepository, never()).save(any(Ship.class));
  }

  @Test
  void importShips_starjumpEmptyCanvas_returnsAllZero() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    MockMultipartFile file =
        multipartFile(
            """
            { "type": "starjumpFleetviewer", "version": 1, "canvasItems": [] }
            """);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of());

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(shipRepository, never()).save(any(Ship.class));
  }

  @Test
  void importShips_fleetyardsFormat_isAutoDetectedAndImported() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType spirit = shipTypeWithName("A1 Spirit");

    String fleetyardsJson =
        """
        [{
          "name":             "A1 Spirit",
          "slug":             "crus-a1-spirit",
          "shipCode":         "crus_spirit_a1",
          "manufacturerName": "Crusader Industries",
          "manufacturerCode": "CRUS",
          "shipName":         "Koto",
          "wanted":           false,
          "flagship":         false,
          "public":           true,
          "nameVisible":      true,
          "saleNotify":       false,
          "groups":           [],
          "modules":          [],
          "upgrades":         []
        }]
        """;
    MockMultipartFile file = multipartFile(fleetyardsJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(spirit));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    verify(shipRepository).save(any(Ship.class));
  }

  @Test
  void importShips_fleetyardsCustomShipName_isSetAsIndividualName() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType galaxy = shipTypeWithName("Galaxy");

    String fleetyardsJson =
        """
        [{
          "name":             "Galaxy",
          "slug":             "rsi-galaxy",
          "manufacturerCode": "RSI",
          "shipName":         "Valenza",
          "nameVisible":      true
        }]
        """;
    MockMultipartFile file = multipartFile(fleetyardsJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(galaxy));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    hangarImportService.importShips(userId, file);

    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Valenza");
    assertThat(captor.getValue().getInsurance()).isEqualTo(FleetExportParser.DEFAULT_INSURANCE);
  }

  @Test
  void importShips_fleetyardsShipNameEchoesModelName_individualNameStaysNull() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType galaxy = shipTypeWithName("Galaxy");

    String fleetyardsJson =
        """
        [{
          "name":             "Galaxy",
          "slug":             "rsi-galaxy",
          "shipCode":         "rsi_galaxy",
          "manufacturerCode": "RSI",
          "shipName":         "Galaxy"
        }]
        """;
    MockMultipartFile file = multipartFile(fleetyardsJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(galaxy));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    hangarImportService.importShips(userId, file);

    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isNull();
    assertThat(captor.getValue().getShipType()).isSameAs(galaxy);
  }

  @Test
  void importShips_fleetyardsSlugFallback_resolvesViaScwikiSlugWhenNameMisses() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType galaxy = shipTypeWithSlugs("Canonical Name", null, "rsi-galaxy");

    String fleetyardsJson =
        """
        [{
          "name":             "Unmatched Label",
          "slug":             "rsi-galaxy",
          "shipCode":         "rsi_galaxy",
          "manufacturerCode": "RSI"
        }]
        """;
    MockMultipartFile file = multipartFile(fleetyardsJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(galaxy));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(galaxy);
  }

  @Test
  void importShips_fleetyardsMixedResolution() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType galaxy = shipTypeWithName("Galaxy");
    ShipType hercules = shipTypeWithName("M2 Hercules Starlifter");
    ShipType perseus = shipTypeWithSlugs("Canonical Perseus", null, "rsi-perseus");

    String fleetyardsJson =
        """
        [
          { "name":"Galaxy", "slug":"rsi-galaxy", "shipCode":"rsi_galaxy",
            "manufacturerCode":"RSI" },
          { "name":"M2 Hercules", "slug":"crus-m2-hercules", "shipCode":"crus_starlifter_m2",
            "manufacturerCode":"CRUS" },
          { "name":"Some Label The Matcher Cannot Resolve", "slug":"rsi-perseus",
            "shipCode":"rsi_perseus", "manufacturerCode":"RSI" },
          { "name":"Alien Xyz", "slug":"alien-xyz", "shipCode":"alien_xyz",
            "manufacturerCode":"ALN" }
        ]
        """;
    MockMultipartFile file = multipartFile(fleetyardsJson);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(galaxy, hercules, perseus));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(3);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("Alien Xyz");
    verify(shipRepository, times(3)).save(any(Ship.class));
  }

  private static ShipType shipTypeWithName(String name) {
    ShipType type = new ShipType();
    type.setId(UUID.randomUUID());
    type.setName(name);
    return type;
  }

  private static ShipType shipTypeWithSlugs(String name, String uexSlug, String scwikiSlug) {
    ShipType type = shipTypeWithName(name);
    type.setUexSlug(uexSlug);
    type.setScwikiSlug(scwikiSlug);
    return type;
  }

  @Test
  void importShips_fileExceedingSizeCap_rejectedBeforeParsing() {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getSize()).thenReturn(9L * 1024 * 1024);

    assertThrows(
        BadRequestException.class, () -> hangarImportService.importShips(UUID.randomUUID(), file));
  }

  @Test
  void importShips_membershiplessImporter_createsOwnerlessShip() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("135c");

    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(ownerScopeService.resolveOrgUnitForImport(any())).thenReturn(null);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getOwningOrgUnit()).isNull();
  }

  @Test
  void importShips_multiMembershipImporter_surfacesBadRequest() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("135c");

    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(ownerScopeService.resolveOrgUnitForImport(any()))
        .thenThrow(new BadRequestException("multi-membership importer needs a picker"));

    assertThrows(BadRequestException.class, () -> hangarImportService.importShips(userId, file));
    verify(shipRepository, never()).save(any(Ship.class));
  }

  private static MockMultipartFile multipartFile(String json) {
    return new MockMultipartFile(
        "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void importShips_hangarExtensionJson_createsShipsWithStatusAndOwnInsurance() {
    UUID userId = UUID.randomUUID();
    User user = userWithId(userId);
    ShipType cat = shipTypeWithName("Caterpillar");
    ShipType arrastra = shipTypeWithName("Arrastra");

    String json =
        """
        [
          {"manufacturer":{"code":"DRAK","name":"Drake Interplanetary"},"code":"DRAK_Caterpillar",
           "matrix":"Caterpillar","status":"Flight-Ready","insurance":"LTI","name":"Caterpillar"},
          {"manufacturer":{"code":"DRAK","name":"Drake Interplanetary"},"code":"DRAK_Caterpillar",
           "matrix":"Caterpillar","status":"Flight-Ready","insurance":"6MI","name":"Caterpillar"},
          {"manufacturer":{"code":"RSI","name":"Roberts Space Industries"},"code":"RSI_Arrastra",
           "matrix":"Arrastra","status":"In-Concept","insurance":"120MI","name":"Arrastra"}
        ]
        """;

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(cat, arrastra));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result =
        hangarImportService.importShips(userId, multipartFile(json));

    assertThat(result.importedCount()).isEqualTo(3);
    assertThat(result.updatedCount()).isZero();
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository, times(3)).save(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(Ship::getInsurance, Ship::getProductionStatus)
        .containsExactly(
            tuple("LTI", ShipProductionStatus.FLIGHT_READY),
            tuple("6", ShipProductionStatus.FLIGHT_READY),
            tuple("120", ShipProductionStatus.IN_CONCEPT));
  }

  @Test
  void importShips_hangarExtensionJson_existingShip_updatesStatusWithoutCreating() {
    UUID userId = UUID.randomUUID();
    User user = userWithId(userId);
    ShipType cat = shipTypeWithName("Caterpillar");
    List<Ship> hangar = owned(cat, 1);

    String json =
        """
        [{"matrix":"Caterpillar","status":"Flight-Ready","insurance":"LTI","name":"Caterpillar"}]
        """;

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(cat));
    when(shipRepository.lockAllOwnedByOrderById(userId)).thenReturn(hangar);

    FleetviewImportResponseDto result =
        hangarImportService.importShips(userId, multipartFile(json));

    assertThat(result.importedCount()).isZero();
    assertThat(result.duplicateCount()).isEqualTo(1);
    assertThat(result.updatedCount()).isEqualTo(1);
    assertThat(hangar.getFirst().getProductionStatus())
        .isEqualTo(ShipProductionStatus.FLIGHT_READY);
    assertThat(hangar.getFirst().getInsurance()).isEqualTo("0");
    verify(shipRepository, never()).save(any(Ship.class));
    verify(auditService)
        .record(
            eq(AuditEventType.HANGAR_IMPORTED),
            isNull(),
            isNull(),
            eq(userId),
            argThat(d -> d.toString().contains("updated=1")));
  }

  @Test
  void importShips_hangarExtensionJson_slugFallback_resolvesViaUexSlug() {
    UUID userId = UUID.randomUUID();
    User user = userWithId(userId);
    ShipType type = shipTypeWithSlugs("600i Explorer Edition", "600i-explorer", null);

    String json =
        """
        [{"matrix":"Six Hundred","status":"Flight-Ready","insurance":"LTI","name":"600i-explorer"}]
        """;

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result =
        hangarImportService.importShips(userId, multipartFile(json));

    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).isEmpty();
  }

  @Test
  void importShips_hangarExtensionCsv_importsOnlyShipRowsWithPledgeData() {
    UUID userId = UUID.randomUUID();
    User user = userWithId(userId);
    ShipType centaurus = shipTypeWithName("Constellation Mk V Centaurus");
    ShipType skylark = shipTypeWithName("Skylark");

    String csv =
        HEADER
            + """
            "1001","October 14, 2026","1,275.00 USD","yes","no","Centaurus Pack","RSI Constellation Mk V Centaurus","Ship"
            "1001","October 14, 2026","1,275.00 USD","yes","no","Centaurus Pack","RSI Skylark","Included Ship"
            "1001","October 14, 2026","1,275.00 USD","yes","no","Centaurus Pack","Lifetime Insurance","Insurance"
            "1001","October 14, 2026","1,275.00 USD","yes","no","Centaurus Pack","Skylark - Magnitude Paint","Skin"
            "1001","October 14, 2026","1,275.00 USD","yes","no","Centaurus Pack","Revel & York Hangar",""
            "1002","October 10, 2026","0.00 USD","no","no","Reward","Helios IP-10","Component"
            """;

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(centaurus, skylark));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, csvFile(csv));

    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.skippedShips()).isEmpty();
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository, times(2)).save(captor.capture());
    Ship ship = captor.getAllValues().getFirst();
    assertThat(ship.getShipType()).isSameAs(centaurus);
    assertThat(ship.getInsurance()).isEqualTo("LTI");
    assertThat(ship.getPledgeId()).isEqualTo("1001");
    assertThat(ship.getPledgeDate()).isEqualTo(LocalDate.of(2026, 10, 14));
    assertThat(ship.getPledgeCost()).isEqualByComparingTo("1275.00");
    assertThat(ship.getPledgeCostCurrency()).isEqualTo("USD");
    assertThat(ship.getMeltable()).isTrue();
    assertThat(ship.getGiftable()).isFalse();
    assertThat(captor.getAllValues().get(1).getShipType()).isSameAs(skylark);
    assertThat(captor.getAllValues().get(1).getPledgeId()).isEqualTo("1001");
  }

  @Test
  void importShips_hangarExtensionCsv_reimport_matchesByPledgeIdAndCreatesNothing() {
    UUID userId = UUID.randomUUID();
    User user = userWithId(userId);
    ShipType fury = shipTypeWithName("Fury");
    List<Ship> hangar = owned(fury, 3);
    hangar.get(0).setPledgeId("2001");
    hangar.get(1).setPledgeId("2002");
    hangar.get(2).setPledgeId("2002");

    String csv =
        HEADER
            + """
            "2002","May 1, 2025","90.00 USD","yes","yes","Fury Pack","Mirai Fury","Ship"
            "2002","May 1, 2025","90.00 USD","yes","yes","Fury Pack","Mirai Fury","Ship"
            """;

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(fury));
    when(shipRepository.lockAllOwnedByOrderById(userId)).thenReturn(hangar);

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, csvFile(csv));

    assertThat(result.importedCount()).isZero();
    assertThat(result.duplicateCount()).isEqualTo(2);
    assertThat(result.updatedCount()).isEqualTo(2);
    assertThat(hangar.get(0).getPledgeDate()).isNull();
    assertThat(hangar.get(1).getPledgeDate()).isEqualTo(LocalDate.of(2025, 5, 1));
    assertThat(hangar.get(2).getPledgeDate()).isEqualTo(LocalDate.of(2025, 5, 1));
    verify(shipRepository, never()).save(any(Ship.class));
  }

  @Test
  void importShips_hangarExtensionCsv_fillsShipWithoutPledgePreferringSameInsurance() {
    UUID userId = UUID.randomUUID();
    User user = userWithId(userId);
    ShipType cat = shipTypeWithName("Caterpillar");
    List<Ship> hangar = owned(cat, 2);
    hangar.get(0).setInsurance("6");
    hangar.get(1).setInsurance("LTI");

    String csv =
        HEADER
            + """
            "3001","June 2, 2020","295.00 USD","yes","yes","Caterpillar","Drake Caterpillar","Ship"
            "3001","June 2, 2020","295.00 USD","yes","yes","Caterpillar","Lifetime Insurance","Insurance"
            """;

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(cat));
    when(shipRepository.lockAllOwnedByOrderById(userId)).thenReturn(hangar);

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, csvFile(csv));

    assertThat(result.importedCount()).isZero();
    assertThat(result.updatedCount()).isEqualTo(1);
    assertThat(hangar.get(0).getPledgeId()).isNull();
    assertThat(hangar.get(1).getPledgeId()).isEqualTo("3001");
  }

  @Test
  void importShips_hangarExtensionCsv_unchangedReimport_recordsNothing() {
    UUID userId = UUID.randomUUID();
    User user = userWithId(userId);
    ShipType cat = shipTypeWithName("Caterpillar");
    List<Ship> hangar = owned(cat, 1);
    Ship ship = hangar.getFirst();
    ship.setPledgeId("3001");
    ship.setPledgeDate(LocalDate.of(2020, 6, 2));
    ship.setPledgeCost(new BigDecimal("295.0"));
    ship.setPledgeCostCurrency("USD");
    ship.setMeltable(true);
    ship.setGiftable(true);

    String csv =
        HEADER
            + """
            "3001","June 2, 2020","295.00 USD","yes","yes","Caterpillar","Drake Caterpillar","Ship"
            """;

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(cat));
    when(shipRepository.lockAllOwnedByOrderById(userId)).thenReturn(hangar);

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, csvFile(csv));

    assertThat(result.updatedCount()).isZero();
    assertThat(result.duplicateCount()).isEqualTo(1);
    verifyNoInteractions(auditService);
    verifyNoInteractions(unitLiveSync);
  }

  @Test
  void importShips_hangarExtensionCsv_unknownShipIsSkippedButNonShipsAreNot() {
    UUID userId = UUID.randomUUID();
    User user = userWithId(userId);

    String csv =
        "﻿"
            + HEADER.replace("\n", "\r\n")
            + "\"4001\",\"January 1, 2024\",\"5.00 USD\",\"no\",\"no\",\"Odd, Pack\","
            + "\"Mystery Craft\",\"Ship\"\r\n"
            + "\"4001\",\"January 1, 2024\",\"5.00 USD\",\"no\",\"no\",\"Odd, Pack\","
            + "\"Gemini R97 \"\"Runic\"\" Shotgun\",\"FPS Equipment\"\r\n";

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(shipTypeWithName("Caterpillar")));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, csvFile(csv));

    assertThat(result.importedCount()).isZero();
    assertThat(result.skippedShips()).containsExactly("Mystery Craft");
  }

  @Test
  void importShips_hangarExtensionCsv_manufacturerPrefixDoesNotMakeSiblingsAmbiguous() {
    UUID userId = UUID.randomUUID();
    User user = userWithId(userId);
    ShipType fury = shipTypeWithName("Fury");
    ShipType furyLx = shipTypeWithName("Fury LX");
    ShipType hercules = shipTypeWithName("C2 Hercules Starlifter");

    String csv =
        HEADER
            + """
            "5001","May 1, 2025","90.00 USD","yes","yes","Pack","Mirai Fury LX","Ship"
            "5001","May 1, 2025","90.00 USD","yes","yes","Pack","Mirai Fury","Ship"
            "5002","May 1, 2025","400.00 USD","yes","yes","C2","Crusader C2 Hercules","Ship"
            """;

    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(fury, furyLx, hercules));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, csvFile(csv));

    assertThat(result.skippedShips()).isEmpty();
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository, times(3)).save(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(Ship::getShipType)
        .containsExactly(furyLx, fury, hercules);
  }

  @Test
  void importShips_csvWithoutPledgeIdColumn_throws400() {
    MockMultipartFile file = csvFile("Name,Type\nCaterpillar,Ship\n");

    assertThrows(
        BadRequestException.class, () -> hangarImportService.importShips(UUID.randomUUID(), file));
  }

  private static final String HEADER =
      "Pledge ID,Pledge Date,Pledge Cost,Meltable,Giftable,Pledge Name,Pledge Content,Content"
          + " Type\n";

  private static User userWithId(UUID userId) {
    User user = new User();
    user.setId(userId);
    return user;
  }

  private static MockMultipartFile csvFile(String csv) {
    return new MockMultipartFile(
        "file", "my-hangar.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
  }

  /** {@code count} pledge-less hangar ships of one type, with insurance {@code "0"}. */
  private static List<Ship> owned(ShipType type, int count) {
    List<Ship> ships = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Ship ship = new Ship();
      ship.setId(UUID.randomUUID());
      ship.setShipType(type);
      ship.setInsurance("0");
      ships.add(ship);
    }
    return ships;
  }

  /** The two lists one after the other. */
  private static List<Ship> concat(List<Ship> first, List<Ship> second) {
    List<Ship> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }
}
