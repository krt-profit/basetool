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
import org.mockito.InjectMocks;
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

  @InjectMocks private HangarImportService hangarImportService;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @BeforeEach
  void injectObjectMapper() throws Exception {
    // Inject the real ObjectMapper into the service via reflection
    var field = HangarImportService.class.getDeclaredField("objectMapper");
    field.setAccessible(true);
    field.set(hangarImportService, objectMapper);
    // Post-R9 D3 (V101): the import flow stamps owning_org_unit via the shared resolver. Tests
    // don't care which OrgUnit is returned — they only verify ship creation count + shape — so
    // return a stub Squadron for every call. Lenient because not every test triggers ship saves.
    de.greluc.krt.profit.basetool.backend.model.Squadron stubSquadron =
        new de.greluc.krt.profit.basetool.backend.model.Squadron();
    stubSquadron.setId(UUID.randomUUID());
    org.mockito.Mockito.lenient()
        .when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(any(), any()))
        .thenReturn(stubSquadron);
  }

  // -------------------------------------------------------------------------
  // Happy path: all ships matched, none in hangar yet → all imported
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_allMatched_importsAllShips() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type135c, typeZeus));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type135c.getId())).thenReturn(0L);
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, typeZeus.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.skippedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(0);
    assertThat(result.skippedShips()).isEmpty();
    assertThat(result.duplicateShips()).isEmpty();
    verify(shipRepository, times(2)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Partial match: one matched, one not found in DB
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_partialMatch_skipsUnknownShips() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type135c));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type135c.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("unknown alien ship");
    verify(shipRepository, times(1)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Duplicate handling: JSON has 3×, hangar has 0 → 3 created
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_triplicateInJson_hangarEmpty_createsAllThree() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(3);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(shipRepository, times(3)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Duplicate handling: JSON has 3×, hangar has 1 → 2 created
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_triplicateInJson_hangarHasOne_createsTwoMore() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(1L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(shipRepository, times(2)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Duplicate handling: JSON has 3×, hangar has 3 → none created (skipped)
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_triplicateInJson_hangarAlreadyHasThree_createsNone() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(3L);

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(3);
    verify(shipRepository, never()).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Duplicate handling: JSON has 3×, hangar has 5 → none created, no deletion
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_triplicateInJson_hangarHasFive_createsNoneAndDoesNotDelete() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(5L);

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(3);
    verify(shipRepository, never()).save(any(Ship.class));
    verify(shipRepository, never()).delete(any());
    verify(shipRepository, never()).deleteAll(any());
  }

  // -------------------------------------------------------------------------
  // Mixed: JSON has 1× ship A and 3× ship B; hangar has 2× A, 1× B
  // → 0 more A (don't delete surplus), 2 more B
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_mixed_partialCreation() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(typeA, typeB));
    // Hangar: 2× vulture (JSON only has 1 → surplus, no creation), 1× aurora mr (JSON has 3 → 2
    // more)
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, typeA.getId())).thenReturn(2L);
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, typeB.getId())).thenReturn(1L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: 0 vulture created (surplus), 2 aurora mr created
    assertThat(result.importedCount()).isEqualTo(2);
    // vulture (1 in JSON) is "already sufficient" → counted in duplicateCount
    assertThat(result.duplicateCount()).isEqualTo(1);
    verify(shipRepository, times(2)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Default insurance value
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_setsDefaultInsurance() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("vulture");

    String json =
        """
        [{"name":"vulture","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    hangarImportService.importShips(userId, file);

    // Then
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getInsurance()).isEqualTo(HangarImportService.DEFAULT_INSURANCE);
  }

  // -------------------------------------------------------------------------
  // Individual ship name is transferred if present
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_setsIndividualShipName_whenPresent() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("890 jump");

    String json =
        """
        [{"name":"890 jump","shipname":"Stella Aeterna","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    hangarImportService.importShips(userId, file);

    // Then
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Stella Aeterna");
  }

  // -------------------------------------------------------------------------
  // Tolerant matching: case-only difference (regression of the original
  // findByNameIgnoreCase semantics under the new index)
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_caseOnlyDifference_stillMatches() {
    // Given: DB has "atls", JSON uses uppercase "ATLS"
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("atls");

    String json =
        """
        [{"name":"ATLS","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // Tolerant matching: hyphen in DB, no hyphen in JSON
  // ("L-21 Wolf" canonical vs "L21 Wolf" in the export)
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_hyphenInDbNotInJson_stillMatchesViaNormalisation() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType wolf = shipTypeWithName("L-21 Wolf");

    String json =
        """
        [{"name":"L21 Wolf","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(wolf));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, wolf.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // Tolerant matching: hyphen in JSON, no hyphen in DB (reverse direction)
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_hyphenInJsonNotInDb_stillMatchesViaNormalisation() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType wolf = shipTypeWithName("L21 Wolf");

    String json =
        """
        [{"name":"L-21 Wolf","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(wolf));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, wolf.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // Tolerant matching: two JSON spellings of the same ship aggregate to one
  // ShipType and the duplicate-count semantics keep working.
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_twoSpellingsSameShip_aggregateToSameType() {
    // Given: DB canonical "L-21 Wolf", JSON contains BOTH the hyphenated and
    // the un-hyphenated form — both should resolve to the same ShipType so
    // the import counts them together (one create, not two).
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(wolf));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, wolf.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: 2 entries in JSON, both for the same canonical ShipType → 2 created
    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.skippedCount()).isEqualTo(0);
    verify(shipRepository, times(2)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Tolerant matching: extra whitespace and trailing punctuation in JSON
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_extraWhitespaceInJson_stillMatches() {
    // Given: DB canonical "Cyclone-AA"; JSON variant "cyclone aa" with extra spaces
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType cyclone = shipTypeWithName("Cyclone-AA");

    String json =
        """
        [{"name":"  cyclone   aa  ","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(cyclone));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, cyclone.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // Skipped ships preserve original casing and dedupe case-insensitively
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_skippedList_preservesOriginalCasingAndDedupes() {
    // Given: DB knows nothing; JSON has the same unknown ship in two casings
    // plus one truly distinct unknown.
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of());

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: 2 entries in skipped list (deduped on case), first-seen casing preserved
    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(2);
    assertThat(result.skippedShips()).containsExactly("Fictional Ship", "Another Unknown");
    verify(shipRepository, never()).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Defensive: ShipType rows with blank names must not poison the lookup
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_shipTypeWithBlankName_isIgnoredInIndex() {
    // Given: a stray ShipType with a blank name (data hygiene defect) sits next to
    // the real one. The blank-name row must not match anything in the import.
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(blank, real));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, real.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
  }

  // -------------------------------------------------------------------------
  // Stage 3 (fv-tokens ⊆ uex-tokens, unique): fv name is a strict abbreviation
  // of the canonical UEX name. Real-world cases verified against the live UEX
  // /vehicles dump:
  //   "A2 Hercules"          -> "A2 Hercules Starlifter"   (uex adds "Starlifter")
  //   "Ares Inferno"         -> "Ares Inferno Starfighter" (uex adds "Starfighter")
  //   "C8R Pisces"           -> "C8R Pisces Rescue"        (uex adds "Rescue")
  //   "Aurora MR"            -> "Aurora Mk I MR"           (uex adds "Mk I" mid-name)
  //   "Mercury"              -> "Mercury Star Runner"      (uex adds two suffix tokens)
  //   "Nova"                 -> "Nova Tank"                (uex adds "Tank")
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_fvAbbreviatesUexCanonicalSuffix_resolvesUniquely() {
    // Given: UEX canonical name is "A2 Hercules Starlifter"; fleetview ships only
    // "A2 Hercules". Stage 3 should pick up the suffix-drift.
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType hercules = shipTypeWithName("A2 Hercules Starlifter");
    // Decoy: same hull family but different prefix token "C2" — must not match.
    ShipType c2Hercules = shipTypeWithName("C2 Hercules Starlifter");

    String json =
        """
        [{"name":"A2 Hercules","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(hercules, c2Hercules));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, hercules.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(hercules);
  }

  @Test
  void importFleetview_fvAbbreviatesAuroraVariant_resolvesToMkI() {
    // Given: UEX has all six Aurora Mk I sub-variants plus "Aurora Mk II".
    // Fleetview shorthand "Aurora MR" must pick exactly "Aurora Mk I MR" — the
    // only candidate whose tokens are a superset of {aurora, mr}.
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll())
        .thenReturn(List.of(mkICl, mkIEs, mkILn, mkILx, mkIMr, mkIIPlain));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, mkIMr.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(mkIMr);
  }

  // -------------------------------------------------------------------------
  // Stage 3 token-set comparison is reordering-tolerant: "Pirate Gladius" in
  // the fleetview matches UEX's canonical "Gladius Pirate" because the token
  // sets are equal, even though the original token order differs.
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_tokenReorderingMatches() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(plain, pirate, valiant));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, pirate.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: "Pirate Gladius" set == "Gladius Pirate" set → unique match.
    // "Gladius" alone is uex⊆fv (Stage 4) but Stage 3 short-circuits because it
    // already found exactly one fv⊆uex hit, so the bare "Gladius" does not steal
    // the match.
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(pirate);
  }

  // -------------------------------------------------------------------------
  // Stage 4 (uex-tokens ⊆ fv-tokens, unique): fv name is longer than UEX's
  // canonical short form. Real-world case from the fleetview export:
  //   "Ursa Rover" -> UEX "Ursa" (the Mk-I-era marketing name was dropped
  //   when CIG repositioned the line; Fleetview still emits "Ursa Rover").
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_uexShorterThanFv_resolvesViaStage4() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(ursa, ursaFortuna, ursaMedivac));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, ursa.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: only "Ursa" satisfies uex⊆fv ({ursa} ⊆ {ursa, rover}); "Ursa Fortuna"
    // and "Ursa Medivac" fail because "fortuna"/"medivac" are not in {ursa, rover}.
    assertThat(result.importedCount()).isEqualTo(1);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(ursa);
  }

  // -------------------------------------------------------------------------
  // Stage 3 ambiguity must NOT fall through to Stage 4 — if the fv abbreviation
  // is ambiguous between several Mk-suffix variants, the entry is left skipped
  // so the user has to disambiguate, instead of silently picking the wrong one.
  // Real-world case: "F7C-M Super Hornet" matches Mk I, Heartseeker Mk I, AND
  // Mk II — all three contain {f7c, m, super, hornet} as a subset.
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_stage3Ambiguous_skipsInsteadOfGuessing() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(mkI, heartseeker, mkII));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("F7C-M Super Hornet");
    verify(shipRepository, never()).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Stage 4 must also be unambiguous — if multiple uex names are subsets of the
  // fv token set, skip rather than guess. (Concocted scenario; the live UEX
  // dump does not currently exhibit this, but the invariant should hold.)
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_stage4Ambiguous_skipsInsteadOfGuessing() {
    // Given: two ShipTypes, both subsets of the fv tokens — must not pick one.
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(foo, bar));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: both {foo} and {bar} are subsets of {foo, bar}; neither wins.
    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("Foo Bar");
    verify(shipRepository, never()).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Stage precedence: when a Stage-1 exact match exists, no later stage may
  // override it. Regression guard for the case where a longer UEX name
  // ALSO contains the exact fv tokens — Stage 1 must win.
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_exactMatchWinsOverTokenSubset() {
    // Given: fv "Gladius" matches the bare "Gladius" exactly. The longer
    // "Gladius Pirate" / "Gladius Valiant" must NOT be considered.
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(plain, pirate, valiant));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, plain.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(plain);
  }

  // -------------------------------------------------------------------------
  // Empty file → 400
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_emptyFile_throws400() {
    // Given
    UUID userId = UUID.randomUUID();
    MockMultipartFile emptyFile =
        new MockMultipartFile("file", "fleetview.json", "application/json", new byte[0]);

    // When / Then
    assertThrows(
        BadRequestException.class, () -> hangarImportService.importShips(userId, emptyFile));
  }

  // -------------------------------------------------------------------------
  // Invalid JSON → 400
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_invalidJson_throws400() {
    // Given
    UUID userId = UUID.randomUUID();
    MockMultipartFile file = multipartFile("THIS IS NOT JSON");

    // When / Then
    assertThrows(BadRequestException.class, () -> hangarImportService.importShips(userId, file));
  }

  // -------------------------------------------------------------------------
  // Unknown user → 404
  // -------------------------------------------------------------------------

  @Test
  void importFleetview_unknownUser_throws404() {
    // Given
    UUID userId = UUID.randomUUID();
    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    // When / Then
    assertThrows(NotFoundException.class, () -> hangarImportService.importShips(userId, file));
  }

  // -------------------------------------------------------------------------
  // HangarXPLOR Shiplist format: basic happy path. Same matcher pipeline as
  // Fleetview, just a different parse layer. The probe field that triggers
  // shiplist parsing is `pledge_id` on the first element.
  // -------------------------------------------------------------------------

  @Test
  void importShips_shiplistFormat_isAutoDetectedAndImported() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(polaris));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, polaris.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    verify(shipRepository).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Shiplist `lti: true` translates into insurance "LTI" on the new ship row.
  // -------------------------------------------------------------------------

  @Test
  void importShips_shiplistLtiTrue_setsLtiInsurance() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    hangarImportService.importShips(userId, file);

    // Then
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getInsurance()).isEqualTo(HangarImportService.LTI_INSURANCE);
  }

  // -------------------------------------------------------------------------
  // Shiplist `lti: false` falls back to the neutral default insurance ("0").
  // We know it is *not* lifetime, but we do not know the month count, so the
  // safe default applies.
  // -------------------------------------------------------------------------

  @Test
  void importShips_shiplistLtiFalse_fallsBackToDefaultInsurance() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    hangarImportService.importShips(userId, file);

    // Then
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getInsurance()).isEqualTo(HangarImportService.DEFAULT_INSURANCE);
  }

  // -------------------------------------------------------------------------
  // Shiplist `ship_name` that is genuinely different from `name` becomes the
  // individual ship name on the imported row. Real-world example:
  // ship_name="KRT Olymp" / name="600i Explorer".
  // -------------------------------------------------------------------------

  @Test
  void importShips_shiplistCustomShipName_isSetAsIndividualName() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    hangarImportService.importShips(userId, file);

    // Then
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("KRT Olymp");
  }

  // -------------------------------------------------------------------------
  // Shiplist `ship_name` that is an abbreviation/echo of `name` is NOT used
  // as an individual ship name — HangarXPLOR commonly emits ship_name="325a"
  // for name="325a Fighter", which is just a redundant short label, not a
  // custom name the user typed in.
  // -------------------------------------------------------------------------

  @Test
  void importShips_shiplistShipNameEchoesModelName_individualNameStaysNull() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    hangarImportService.importShips(userId, file);

    // Then: model name resolves via Stage 4 (uex subset of fv), individual name stays null
    // because "325a" is a substring of normalised "325afighter".
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isNull();
    assertThat(captor.getValue().getShipType()).isSameAs(type);
  }

  // -------------------------------------------------------------------------
  // Shiplist `entity_type != "ship"` (module / package / paint) is silently
  // dropped at the parse step — never reaches the matcher, never shows up in
  // skippedShips. Defensive against HangarXPLOR exporting non-ship rows.
  // -------------------------------------------------------------------------

  @Test
  void importShips_shiplistNonShipEntityType_isDroppedAtParseStep() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: only the Polaris was created; the paint row never reached the matcher,
    // so it is also NOT counted as skipped.
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    assertThat(result.skippedShips()).isEmpty();
    verify(shipRepository, times(1)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Format detection: the probe inspects the FIRST array element. Fleetview
  // payloads (no pledge_id, no ship_code) must continue to parse correctly
  // after the new shiplist branch was added.
  // -------------------------------------------------------------------------

  @Test
  void importShips_fleetviewFormatStillRecognisedAfterShiplistAddition() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("135c");

    String fleetviewJson =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(fleetviewJson);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // Format detection: JSON whose first element matches neither marker (no
  // pledge_id/ship_code AND no shipname/type) → 400 with a clear message.
  // -------------------------------------------------------------------------

  @Test
  void importShips_unknownFormat_throws400() {
    // Given
    UUID userId = UUID.randomUUID();
    String mysteryJson =
        """
        [{"foo":"bar","baz":42}]
        """;
    MockMultipartFile file = multipartFile(mysteryJson);

    // When / Then
    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> hangarImportService.importShips(userId, file));
    assertThat(ex.getMessage()).contains("Unknown ship-list format");
  }

  // -------------------------------------------------------------------------
  // Format detection: root that is not a JSON array → 400.
  // -------------------------------------------------------------------------

  @Test
  void importShips_rootIsObjectNotArray_throws400() {
    // Given
    UUID userId = UUID.randomUUID();
    MockMultipartFile file =
        multipartFile(
            """
            {"name":"135c","shipname":"","type":"ship"}
            """);

    // When / Then
    assertThrows(BadRequestException.class, () -> hangarImportService.importShips(userId, file));
  }

  // -------------------------------------------------------------------------
  // Format detection: empty array is accepted and yields 0 imports / 0 skips
  // (degenerate but valid input — neither side of the parse branch fires).
  // -------------------------------------------------------------------------

  @Test
  void importShips_emptyArray_returnsAllZero() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    MockMultipartFile file = multipartFile("[]");

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of());

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(shipRepository, never()).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Shiplist resolution exercise mirroring the real input/shiplist.json file:
  // mixed exact + Stage 3 (Hercules token-reorder) + Stage 4 (Ursa Rover -> Ursa)
  // + truly unmatched ("600i Exploration Module" -- no UEX equivalent) all in a
  // single payload. Verifies the four-stage pipeline runs through the shiplist
  // branch end-to-end.
  // -------------------------------------------------------------------------

  @Test
  void importShips_shiplistMixedResolution() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType hercules = shipTypeWithName("A2 Hercules Starlifter"); // Stage 3 reorder
    ShipType ursa = shipTypeWithName("Ursa"); // Stage 4 (uex subset of fv)
    ShipType polaris = shipTypeWithName("Polaris"); // Stage 1 exact

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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(hercules, ursa, polaris));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, hercules.getId())).thenReturn(0L);
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, ursa.getId())).thenReturn(0L);
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, polaris.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(3);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("600i Exploration Module");
    verify(shipRepository, times(3)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // StarJump FleetViewer ("Hangar Link"): object root with a canvasItems array.
  // SHIP items are imported (matched on defaultText via the same pipeline);
  // decorative TEXTGROUP items are dropped at the parse step.
  // -------------------------------------------------------------------------

  @Test
  void importShips_starjumpFormat_isAutoDetectedAndShipsImported() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(perseus, galaxy));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, perseus.getId())).thenReturn(0L);
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, galaxy.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: the two SHIP items import; the two TEXTGROUP items never reach the matcher.
    assertThat(result.importedCount()).isEqualTo(2);
    assertThat(result.skippedCount()).isEqualTo(0);
    verify(shipRepository, times(2)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // StarJump slug fallback: when the FleetViewer defaultText resolves against
  // no ShipType name, the kebab-case shipSlug is matched against ShipType.uexSlug.
  // -------------------------------------------------------------------------

  @Test
  void importShips_starjumpSlugFallback_resolvesViaUexSlugWhenNameMisses() {
    // Given: the display name matches nothing, but the slug equals the ship's UEX slug.
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(zeus));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, zeus.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(zeus);
  }

  @Test
  void importShips_starjumpSlugFallback_resolvesViaScwikiSlugWhenNameAndUexMiss() {
    // Given: name and uexSlug both miss; the SC Wiki slug carries the match.
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(ship));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, ship.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(ship);
  }

  // -------------------------------------------------------------------------
  // StarJump precedence: a name hit must win over a slug hit on a different
  // ShipType, so the slug fallback never overrides a confident name match.
  // -------------------------------------------------------------------------

  @Test
  void importShips_starjumpNameMatchWinsOverSlugMatch() {
    // Given: defaultText "Perseus" matches ShipType A exactly; the slug "decoy-slug"
    // would match ShipType B's UEX slug — but the name stage fires first.
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(perseus, decoy));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, perseus.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(perseus);
  }

  // -------------------------------------------------------------------------
  // StarJump unmatched: a SHIP whose display name and slug both miss is surfaced
  // in skippedShips under its defaultText.
  // -------------------------------------------------------------------------

  @Test
  void importShips_starjumpUnmatchedShip_isSkippedUnderDefaultText() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of());

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("Alien Mystery Ship");
    verify(shipRepository, never()).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // StarJump detection by the type discriminator alone: an object carrying
  // "type":"starjumpFleetviewer" but no (or empty) canvasItems is recognised as
  // FleetViewer and imports as a clean no-op rather than failing the array check.
  // -------------------------------------------------------------------------

  @Test
  void importShips_starjumpEmptyCanvas_returnsAllZero() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    MockMultipartFile file =
        multipartFile(
            """
            { "type": "starjumpFleetviewer", "version": 1, "canvasItems": [] }
            """);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of());

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(0);
    assertThat(result.skippedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(shipRepository, never()).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Fleetyards format (https://fleetyards.net): a flat array keyed by the
  // camelCase shipCode/manufacturerCode pair. Same matcher pipeline as the other
  // formats; only the parse layer differs. The probe field that triggers
  // Fleetyards parsing is `shipCode` (camelCase) on the first element — it must
  // never collide with HangarXPLOR's snake_case `ship_code`.
  // -------------------------------------------------------------------------

  @Test
  void importShips_fleetyardsFormat_isAutoDetectedAndImported() {
    // Given: a full real-world Fleetyards entry (all flags + groups/modules/upgrades arrays).
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(spirit));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, spirit.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    verify(shipRepository).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Fleetyards `shipName` that is genuinely different from `name` becomes the
  // individual ship name; the absence of insurance data falls back to the
  // neutral default. This entry also exercises the `manufacturerCode` half of
  // the probe (no `shipCode` key present).
  // -------------------------------------------------------------------------

  @Test
  void importShips_fleetyardsCustomShipName_isSetAsIndividualName() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(galaxy));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, galaxy.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    hangarImportService.importShips(userId, file);

    // Then
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Valenza");
    assertThat(captor.getValue().getInsurance()).isEqualTo(HangarImportService.DEFAULT_INSURANCE);
  }

  // -------------------------------------------------------------------------
  // Fleetyards `shipName` that merely echoes `name` is NOT used as a custom
  // name — the shared echo heuristic discards it just like the Shiplist mapper.
  // -------------------------------------------------------------------------

  @Test
  void importShips_fleetyardsShipNameEchoesModelName_individualNameStaysNull() {
    // Given
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(galaxy));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, galaxy.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    hangarImportService.importShips(userId, file);

    // Then: normalised "galaxy" is a substring of normalised "galaxy" → echo → individual null.
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getName()).isNull();
    assertThat(captor.getValue().getShipType()).isSameAs(galaxy);
  }

  // -------------------------------------------------------------------------
  // Fleetyards slug fallback: when the display name resolves against no ShipType
  // name, the manufacturer-prefixed kebab-case slug (whose shape mirrors the SC
  // Wiki slug) is matched against ShipType.scwikiSlug.
  // -------------------------------------------------------------------------

  @Test
  void importShips_fleetyardsSlugFallback_resolvesViaScwikiSlugWhenNameMisses() {
    // Given: the name matches nothing, but the Fleetyards slug equals the ship's SC Wiki slug.
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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(galaxy));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, galaxy.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getShipType()).isSameAs(galaxy);
  }

  // -------------------------------------------------------------------------
  // Fleetyards mixed resolution mirroring the real export files: an exact name
  // hit (Galaxy), a Stage-3 suffix-drift hit (M2 Hercules -> "M2 Hercules
  // Starlifter"), a slug fallback (display name misses, scwikiSlug carries the
  // match) and a truly unmatched entry, all in one payload.
  // -------------------------------------------------------------------------

  @Test
  void importShips_fleetyardsMixedResolution() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType galaxy = shipTypeWithName("Galaxy"); // Stage 1 exact
    ShipType hercules = shipTypeWithName("M2 Hercules Starlifter"); // Stage 3 fv subset of uex
    ShipType perseus = shipTypeWithSlugs("Canonical Perseus", null, "rsi-perseus"); // slug fallback

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

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(galaxy, hercules, perseus));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, galaxy.getId())).thenReturn(0L);
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, hercules.getId())).thenReturn(0L);
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, perseus.getId())).thenReturn(0L);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: Galaxy + M2 Hercules + (Perseus via slug) import; only "Alien Xyz" is unmatched.
    assertThat(result.importedCount()).isEqualTo(3);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedShips()).containsExactly("Alien Xyz");
    verify(shipRepository, times(3)).save(any(Ship.class));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

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
    // Security audit gap-fill: an oversized upload is rejected by file.getSize() BEFORE readTree
    // materialises the JSON into an in-memory tree (the user lookup and parsing are never reached).
    MultipartFile file = mock(MultipartFile.class);
    when(file.getSize()).thenReturn(9L * 1024 * 1024); // > 8 MB cap

    assertThrows(
        BadRequestException.class, () -> hangarImportService.importShips(UUID.randomUUID(), file));
  }

  // -------------------------------------------------------------------------
  // Org-unit stamping: a membershipless importer's picker resolver returns null
  // (V132 made owning_org_unit nullable), so the ship must be created ownerless
  // rather than NPE-ing or being rejected. Overrides the @BeforeEach stub that
  // otherwise always returns a Squadron.
  // -------------------------------------------------------------------------

  @Test
  void importShips_membershiplessImporter_createsOwnerlessShip() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("135c");

    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(any(), any())).thenReturn(null);
    when(shipRepository.save(any(Ship.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    FleetviewImportResponseDto result = hangarImportService.importShips(userId, file);

    // Then: the ship is created ownerless (owningOrgUnit == null), not skipped.
    assertThat(result.importedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(0);
    ArgumentCaptor<Ship> captor = ArgumentCaptor.forClass(Ship.class);
    verify(shipRepository).save(captor.capture());
    assertThat(captor.getValue().getOwningOrgUnit()).isNull();
  }

  // -------------------------------------------------------------------------
  // Org-unit stamping: a multi-membership importer's picker resolver throws a
  // 400 (no per-import picker exists yet), which must fail the whole import
  // rather than silently stamping the ship onto an arbitrary org unit — a
  // multi-org tenancy-isolation guard. The resolver is invoked before
  // shipRepository.save, so no ship is ever persisted.
  // -------------------------------------------------------------------------

  @Test
  void importShips_multiMembershipImporter_surfacesBadRequest() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);

    ShipType type = shipTypeWithName("135c");

    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file = multipartFile(json);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(shipTypeRepository.findAll()).thenReturn(List.of(type));
    when(shipRepository.countByOwnerIdAndShipTypeId(userId, type.getId())).thenReturn(0L);
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(any(), any()))
        .thenThrow(new BadRequestException("multi-membership importer needs a picker"));

    // When / Then: the resolver throws before shipRepository.save is ever reached.
    assertThrows(BadRequestException.class, () -> hangarImportService.importShips(userId, file));
    verify(shipRepository, never()).save(any(Ship.class));
  }

  private static MockMultipartFile multipartFile(String json) {
    return new MockMultipartFile(
        "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
  }
}
