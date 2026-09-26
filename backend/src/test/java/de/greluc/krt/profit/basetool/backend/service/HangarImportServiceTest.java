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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.nio.charset.StandardCharsets;
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
 *   <li>Hangar count never exceeds the JSON count: {@code max(0, jsonCount - hangarCount)}
 *       additional ships are created per distinct ship type.
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

  private HangarImportService hangarImportService;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @BeforeEach
  void setUp() {
    hangarImportService =
        new HangarImportService(
            shipRepository, shipTypeRepository, userRepository, objectMapper, ownerScopeService);
    de.greluc.krt.profit.basetool.backend.model.Squadron stubSquadron =
        new de.greluc.krt.profit.basetool.backend.model.Squadron();
    stubSquadron.setId(UUID.randomUUID());
    org.mockito.Mockito.lenient()
        .when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(any(), any()))
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
    when(shipRepository.countShipsPerTypeByOwnerId(userId))
        .thenReturn(List.of(typeCount(type.getId(), 1L)));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.duplicateCount()).isEqualTo(0);
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
    when(shipRepository.countShipsPerTypeByOwnerId(userId))
        .thenReturn(List.of(typeCount(type.getId(), 3L)));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(3);
    verify(shipRepository, never()).save(any(Ship.class));
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
    when(shipRepository.countShipsPerTypeByOwnerId(userId))
        .thenReturn(List.of(typeCount(type.getId(), 5L)));

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
    when(shipRepository.countShipsPerTypeByOwnerId(userId))
        .thenReturn(List.of(typeCount(typeA.getId(), 2L), typeCount(typeB.getId(), 1L)));
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.duplicateCount()).isEqualTo(1);
    verify(shipRepository, times(2)).save(any(Ship.class));
    verify(shipRepository, times(1)).countShipsPerTypeByOwnerId(userId);
    verify(ownerScopeService, times(1)).resolveOrgUnitForPickerOutputNullable(any(), any());
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
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(any(), any())).thenReturn(null);
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
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(any(), any()))
        .thenThrow(new BadRequestException("multi-membership importer needs a picker"));

    assertThrows(BadRequestException.class, () -> hangarImportService.importShips(userId, file));
    verify(shipRepository, never()).save(any(Ship.class));
  }

  private static MockMultipartFile multipartFile(String json) {
    return new MockMultipartFile(
        "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
  }

  /** A grouped-count row of {@code ShipRepository.countShipsPerTypeByOwnerId}. */
  private static ShipRepository.ShipTypeCount typeCount(UUID shipTypeId, long count) {
    return new ShipRepository.ShipTypeCount() {
      @Override
      public UUID getShipTypeId() {
        return shipTypeId;
      }

      @Override
      public long getShipCount() {
        return count;
      }
    };
  }
}
