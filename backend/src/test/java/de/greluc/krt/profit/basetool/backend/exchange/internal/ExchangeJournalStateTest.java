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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintCommands;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipCommands;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ExchangeJournalStateTest {

  private static final UUID MEMBER = UUID.randomUUID();
  private static final String CLIENT = "versekit";
  private static final long TX = 42L;

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final ExchangeChangeRepository changeRepository = mock(ExchangeChangeRepository.class);

  private String json(Map<String, Object> state) {
    return mapper.writeValueAsString(state);
  }

  private ExchangeJournalEntry entry(
      ExchangeJournalAction action, String key, String before, String after) {
    return ExchangeJournalEntry.builder()
        .id(UUID.randomUUID())
        .userId(MEMBER)
        .clientId(CLIENT)
        .installationKey("k".repeat(43))
        .resource(action.getResource())
        .entityKey(key)
        .action(action)
        .beforeState(before)
        .afterState(after)
        .tx(TX)
        .build();
  }

  private void latestChangeIsTheClients(ExchangeResource resource) {
    ExchangeChange change = mock(ExchangeChange.class);
    when(change.getTx()).thenReturn(TX);
    when(changeRepository.findLatestForKey(eq(MEMBER), eq(resource.name()), anyString()))
        .thenReturn(Optional.of(change));
  }

  private ExchangeEntryRestorer.UndoScope scope(Set<UUID> memberships) {
    return new ExchangeEntryRestorer.UndoScope(MEMBER, CLIENT, () -> memberships);
  }

  @Nested
  class Blueprint {

    private final PersonalBlueprintRepository repository = mock(PersonalBlueprintRepository.class);
    private final PersonalBlueprintCommands commands = mock(PersonalBlueprintCommands.class);
    private final ExchangeBlueprintJournal journal =
        new ExchangeBlueprintJournal(repository, commands, changeRepository, mapper);

    @Test
    void theStateIsWrittenInTheJournalsExistingShape() {
      PersonalBlueprint blueprint =
          PersonalBlueprint.builder()
              .productKey("arrow")
              .productName("Arrow")
              .acquiredAt(Instant.parse("2026-03-01T10:00:00Z"))
              .note("gift")
              .build();

      assertThat(json(ExchangeBlueprintJournal.State.of(blueprint).overriding().toJournal()))
          .isEqualTo(
              "{\"productKey\":\"arrow\",\"productName\":\"Arrow\","
                  + "\"acquiredAt\":\"2026-03-01T10:00:00Z\",\"note\":\"gift\",\"override\":true}");
      assertThat(
              json(
                  ExchangeBlueprintJournal.State.of(
                          PersonalBlueprint.builder().productKey("arrow").build())
                      .toJournal()))
          .isEqualTo("{\"productKey\":\"arrow\",\"productName\":null}");
    }

    @Test
    void aSnapshotReadsBackAsTheSameState() {
      ExchangeBlueprintJournal.State state =
          new ExchangeBlueprintJournal.State(
              "arrow", "Arrow", Instant.parse("2026-03-01T10:00:00Z"), "gift", false);

      assertThat(journal.read(json(state.toJournal()))).isEqualTo(state);
    }

    @Test
    void anOlderEntryWithoutTheOptionalKeysStillReadsAndRestores() {
      latestChangeIsTheClients(ExchangeResource.BLUEPRINT);
      when(repository.findByOwnerUserIdAndProductKey(MEMBER, "arrow")).thenReturn(Optional.empty());
      String before = "{\"productKey\":\"arrow\",\"productName\":\"Arrow\"}";

      String reason =
          journal.restore(
              scope(Set.of()),
              List.of(entry(ExchangeJournalAction.BLUEPRINT_REMOVE, "arrow", before, null)));

      assertThat(reason).isNull();
      verify(commands).add(MEMBER, new PersonalBlueprintCreateRequest("arrow", null, null));
    }

    @Test
    void aRemovedBlueprintComesBackWithItsAcquisitionAndNote() {
      latestChangeIsTheClients(ExchangeResource.BLUEPRINT);
      when(repository.findByOwnerUserIdAndProductKey(MEMBER, "arrow")).thenReturn(Optional.empty());
      Instant acquired = Instant.parse("2026-03-01T10:00:00Z");
      String before =
          json(
              ExchangeBlueprintJournal.State.of(
                      PersonalBlueprint.builder()
                          .productKey("arrow")
                          .productName("Arrow")
                          .acquiredAt(acquired)
                          .note("gift")
                          .build())
                  .toJournal());

      journal.restore(
          scope(Set.of()),
          List.of(entry(ExchangeJournalAction.BLUEPRINT_REMOVE, "arrow", before, null)));

      verify(commands).add(MEMBER, new PersonalBlueprintCreateRequest("arrow", acquired, "gift"));
    }

    @Test
    void aStateWithAnUnreadableAcquisitionDateIsGoneInsteadOfLosingTheDate() {
      latestChangeIsTheClients(ExchangeResource.BLUEPRINT);
      String before = "{\"productKey\":\"arrow\",\"productName\":\"Arrow\",\"acquiredAt\":\"x\"}";

      String reason =
          journal.restore(
              scope(Set.of()),
              List.of(entry(ExchangeJournalAction.BLUEPRINT_REMOVE, "arrow", before, null)));

      assertThat(reason).isEqualTo(ExchangeUndoService.GONE);
      verify(commands, never()).add(any(), any());
    }

    @Test
    void aBlueprintChangedSinceIsLeftAlone() {
      when(changeRepository.findLatestForKey(any(), any(), any())).thenReturn(Optional.empty());

      String reason =
          journal.restore(
              scope(Set.of()),
              List.of(entry(ExchangeJournalAction.BLUEPRINT_ADD, "arrow", null, "{}")));

      assertThat(reason).isEqualTo(ExchangeUndoService.CHANGED_AFTERWARDS);
      verify(commands, never()).delete(any(), any());
    }
  }

  @Nested
  class ShipState {

    private final ShipRepository ships = mock(ShipRepository.class);
    private final ShipTypeRepository types = mock(ShipTypeRepository.class);
    private final LocationRepository locations = mock(LocationRepository.class);
    private final ShipCommands commands = mock(ShipCommands.class);
    private final ExchangeShipLinkRepository links = mock(ExchangeShipLinkRepository.class);
    private final ExchangeShipJournal journal =
        new ExchangeShipJournal(ships, types, locations, commands, links, changeRepository, mapper);

    private final UUID typeId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();
    private final UUID unitId = UUID.randomUUID();

    private Ship ship(UUID unit) {
      ShipType type = mock(ShipType.class);
      when(type.getId()).thenReturn(typeId);
      Location location = mock(Location.class);
      when(location.getId()).thenReturn(locationId);
      Ship ship = new Ship();
      ship.setShipType(type);
      ship.setName("Kestrel");
      ship.setInsurance("LTI");
      ship.setLocation(location);
      ship.setFitted(true);
      if (unit != null) {
        OrgUnit orgUnit = mock(OrgUnit.class);
        when(orgUnit.getId()).thenReturn(unit);
        ship.setOwningOrgUnit(orgUnit);
      }
      return ship;
    }

    @Test
    void theStateIsWrittenInTheJournalsExistingShape() {
      assertThat(json(ExchangeShipJournal.State.of(ship(null)).toJournal()))
          .isEqualTo(
              "{\"shipType\":\""
                  + typeId
                  + "\",\"name\":\"Kestrel\",\"insurance\":\"LTI\",\"location\":\""
                  + locationId
                  + "\",\"fitted\":true,\"orgUnit\":null}");
    }

    @Test
    void aSnapshotReadsBackAsTheSameState() {
      ExchangeShipJournal.State state = ExchangeShipJournal.State.of(ship(unitId));

      assertThat(journal.read(json(state.toJournal()))).isEqualTo(state);
      assertThat(state.orgUnit()).isEqualTo(new ExchangeShipJournal.RecordedOrgUnit(unitId));
    }

    @Test
    void anOlderStateWithoutAnOrgUnitRecordsNoUnitAndAnUnreadableOneNeither() {
      String older =
          "{\"shipType\":\""
              + typeId
              + "\",\"name\":null,\"insurance\":\"6\",\"location\":null,\"fitted\":false}";
      String unreadable = older.replace("}", ",\"orgUnit\":\"nope\"}");
      String none = older.replace("}", ",\"orgUnit\":null}");

      assertThat(journal.read(older).orgUnit()).isNull();
      assertThat(journal.read(unreadable).orgUnit()).isNull();
      assertThat(journal.read(none).orgUnit())
          .isEqualTo(new ExchangeShipJournal.RecordedOrgUnit(null));
      assertThat(journal.read(older).synced())
          .isEqualTo(new ExchangeShipJournal.State(typeId, null, "6", null, false, null));
    }

    @Test
    void aRemovedShipIsRecreatedInItsEarlierUnitWhileTheMemberBelongsToIt() {
      latestChangeIsTheClients(ExchangeResource.SHIP);
      UUID shipId = UUID.randomUUID();
      when(ships.lockOwnedById(shipId, MEMBER)).thenReturn(Optional.empty());
      when(ships.existsById(shipId)).thenReturn(false);
      when(types.existsById(typeId)).thenReturn(true);
      when(locations.existsById(locationId)).thenReturn(true);
      String before = json(ExchangeShipJournal.State.of(ship(unitId)).toJournal());

      String reason =
          journal.restore(
              scope(Set.of(unitId)),
              List.of(entry(ExchangeJournalAction.SHIP_REMOVE, shipId.toString(), before, null)));

      assertThat(reason).isNull();
      verify(commands)
          .addShipForClientInOrgUnit(
              MEMBER,
              new ShipRequestDto("Kestrel", typeId, "LTI", locationId, true, null, null),
              unitId);
    }

    @Test
    void anOlderStateWithoutAnOrgUnitIsRecreatedLikeAClientsCreate() {
      latestChangeIsTheClients(ExchangeResource.SHIP);
      UUID shipId = UUID.randomUUID();
      when(ships.lockOwnedById(shipId, MEMBER)).thenReturn(Optional.empty());
      when(types.existsById(typeId)).thenReturn(true);
      String older =
          "{\"shipType\":\""
              + typeId
              + "\",\"name\":\"Old\",\"insurance\":\"LTI\",\"location\":null,\"fitted\":false}";

      journal.restore(
          scope(Set.of(unitId)),
          List.of(entry(ExchangeJournalAction.SHIP_REMOVE, shipId.toString(), older, null)));

      verify(commands)
          .addShipForClient(
              MEMBER, new ShipRequestDto("Old", typeId, "LTI", null, false, null, null));
    }

    @Test
    void aStateWithAnUnreadableLocationIsGoneInsteadOfLosingTheLocation() {
      latestChangeIsTheClients(ExchangeResource.SHIP);
      UUID shipId = UUID.randomUUID();
      String broken =
          "{\"shipType\":\""
              + typeId
              + "\",\"name\":\"Old\",\"insurance\":\"LTI\",\"location\":\"nope\",\"fitted\":false}";

      String reason =
          journal.restore(
              scope(Set.of()),
              List.of(entry(ExchangeJournalAction.SHIP_REMOVE, shipId.toString(), broken, null)));

      assertThat(reason).isEqualTo(ExchangeUndoService.GONE);
      verify(commands, never()).addShipForClient(any(), any());
    }

    @Test
    void aLinkStateReadsBackAsItsInstallationId() {
      assertThat(journal.readLink(json(ExchangeShipJournal.linkState("ext-1")))).isEqualTo("ext-1");
      assertThat(json(ExchangeShipJournal.linkState("ext-1")))
          .isEqualTo("{\"externalId\":\"ext-1\"}");
    }
  }

  @Nested
  class Stock {

    private final ExchangeStockWriteService writer = mock(ExchangeStockWriteService.class);
    private final ExchangeStockJournal journal =
        new ExchangeStockJournal(writer, changeRepository, mapper);
    private static final String LOT =
        "m:" + UUID.randomUUID() + "|l:" + UUID.randomUUID() + "|q:0|s:0";

    @Test
    void theStatesAreWrittenInTheJournalsExistingShape() {
      UUID pool = UUID.fromString("00000000-0000-0000-0000-000000000001");

      assertThat(json(ExchangeStockJournal.State.of(new BigDecimal("12.500"), "SCU").toJournal()))
          .isEqualTo("{\"quantity\":12.500,\"unit\":\"SCU\"}");
      assertThat(
              json(
                  ExchangeStockJournal.State.moved(
                          BigDecimal.TEN, "PIECE", pool, true, BigDecimal.ONE)
                      .toJournal()))
          .isEqualTo(
              "{\"quantity\":10,\"unit\":\"PIECE\",\"orgUnit\":\"" + pool + "\",\"shared\":1}");
    }

    @Test
    void aSnapshotReadsBackAsTheSameState() {
      ExchangeStockJournal.State state =
          ExchangeStockJournal.State.moved(
              new BigDecimal("3.25"), "SCU", UUID.randomUUID(), false, new BigDecimal("1.5"));

      assertThat(journal.read(json(state.toJournal()))).isEqualTo(state);
    }

    @Test
    void anOlderShareAndSetQuantityRestoreThePoolsAndTheQuantity() {
      latestChangeIsTheClients(ExchangeResource.STOCK);
      UUID pool = UUID.randomUUID();
      when(writer.restorePools(eq(MEMBER), eq(LOT), eq("SCU"), any())).thenReturn(true);
      when(writer.restoreLot(eq(MEMBER), eq(LOT), any(), eq("SCU"))).thenReturn(true);
      ExchangeJournalEntry share =
          entry(
              ExchangeJournalAction.STOCK_SHARE,
              LOT,
              "{\"quantity\":5,\"unit\":\"SCU\"}",
              "{\"quantity\":5,\"unit\":\"SCU\",\"orgUnit\":\"" + pool + "\",\"shared\":2}");
      ExchangeJournalEntry set =
          entry(
              ExchangeJournalAction.STOCK_SET_QUANTITY,
              LOT,
              "{\"quantity\":3,\"unit\":\"SCU\"}",
              "{\"quantity\":5,\"unit\":\"SCU\"}");

      String reason = journal.restore(scope(Set.of()), List.of(share, set));

      assertThat(reason).isNull();
      verify(writer).restorePools(MEMBER, LOT, "SCU", Map.of(pool, new BigDecimal("2")));
      verify(writer).restoreLot(MEMBER, LOT, new BigDecimal("3"), "SCU");
    }

    @Test
    void aShareWithoutItsPoolIsGone() {
      latestChangeIsTheClients(ExchangeResource.STOCK);
      ExchangeJournalEntry share =
          entry(
              ExchangeJournalAction.STOCK_SHARE,
              LOT,
              "{\"quantity\":5,\"unit\":\"SCU\"}",
              "{\"quantity\":5,\"unit\":\"SCU\",\"shared\":2}");

      assertThat(journal.restore(scope(Set.of()), List.of(share)))
          .isEqualTo(ExchangeUndoService.GONE);
      verify(writer, never()).restoreLot(any(), any(), any(), any());
    }
  }

  @Nested
  class OrgStock {

    private final ExchangeOrgStockJournal journal =
        new ExchangeOrgStockJournal(
            mock(PoolEntryCommands.class),
            mock(de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands.class),
            mapper);

    @Test
    void aSnapshotReadsBackAsTheSameState() {
      PoolEntryCommands.PoolEntryState state =
          new PoolEntryCommands.PoolEntryState(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "m:x|l:y|q:0|s:0",
              4.0,
              7L,
              Map.of(UUID.randomUUID(), 1.0),
              Map.of());

      assertThat(journal.read(mapper.writeValueAsString(state))).isEqualTo(state);
      assertThat(journal.read(null)).isNull();
    }

    @Test
    void anEntryAsTheWriterJournaledItStillReads() {
      UUID id = UUID.fromString("00000000-0000-0000-0000-00000000000a");
      UUID holder = UUID.fromString("00000000-0000-0000-0000-00000000000b");
      UUID pool = UUID.fromString("00000000-0000-0000-0000-00000000000c");
      UUID jobOrder = UUID.fromString("00000000-0000-0000-0000-00000000000d");
      String stored =
          "{\"id\":\""
              + id
              + "\",\"holder\":\""
              + holder
              + "\",\"poolUnitId\":\""
              + pool
              + "\",\"lotKey\":\"m:x|l:y|q:0|s:0\",\"amount\":4.5,\"version\":3,"
              + "\"jobOrders\":{\""
              + jobOrder
              + "\":1.0},\"missions\":{}}";

      assertThat(journal.read(stored))
          .isEqualTo(
              new PoolEntryCommands.PoolEntryState(
                  id, holder, pool, "m:x|l:y|q:0|s:0", 4.5, 3L, Map.of(jobOrder, 1.0), Map.of()));
    }
  }
}
