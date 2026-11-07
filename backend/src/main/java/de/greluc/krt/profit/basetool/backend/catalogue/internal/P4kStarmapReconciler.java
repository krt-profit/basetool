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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kStarmapObjectDto;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Imports the catalogue's {@code starmapObjects} into {@code starmap_object} (REQ-P4K-034): every
 * record with a GUID is matched by that GUID or added, the P4K's columns are mirrored onto it, and
 * the parent links are set once the whole kind is known, so the order of the records does not
 * matter. A preview reports the same and writes nothing.
 */
@Component
@RequiredArgsConstructor
public class P4kStarmapReconciler {

  /** The kind this reconciler owns. */
  private static final P4kKind KIND = P4kKind.STARMAP_OBJECT;

  private final StarmapObjectRepository repository;

  /**
   * Reconciles every starmap object; a GUID the catalogue repeats is read once.
   *
   * @param records the catalogue's starmap objects
   * @param context the run
   * @return the tree after the run, stored objects included
   */
  @NotNull
  public P4kStarmapTree reconcile(
      @NotNull List<P4kStarmapObjectDto> records, @NotNull P4kImportContext context) {
    Map<UUID, StarmapObject> byGuid = new HashMap<>();
    Map<UUID, UUID> guidById = new HashMap<>();
    for (StarmapObject stored : repository.findAll()) {
      byGuid.put(stored.getP4kUuid(), stored);
      guidById.put(stored.getId(), stored.getP4kUuid());
    }
    Map<UUID, UUID> parents = new HashMap<>();
    byGuid.values().stream()
        .filter(o -> o.getParentId() != null && guidById.containsKey(o.getParentId()))
        .forEach(o -> parents.put(o.getP4kUuid(), guidById.get(o.getParentId())));
    Map<UUID, Pending> run = new LinkedHashMap<>();
    for (P4kStarmapObjectDto record : records) {
      UUID guid = record.parsedGuid();
      if (guid == null || run.containsKey(guid)) {
        continue;
      }
      run.put(guid, write(record, guid, byGuid, context));
    }
    run.forEach((guid, pending) -> {
      linkParent(pending, guid, byGuid, parents, context);
      context.stamp(KIND, pending.record(), pending.row(), guid);
      if (pending.writer() != null) {
        context.getReport().matched(KIND, pending.writer().isChanged());
      }
    });
    return new P4kStarmapTree(byGuid, parents);
  }

  /**
   * Writes one record onto its stored object, or adds the object.
   *
   * @param record the record
   * @param guid its GUID
   * @param byGuid every object by GUID, extended by an added one
   * @param context the run
   * @return the object and, for a matched one, the writer that logged its changes
   */
  @NotNull
  private Pending write(
      @NotNull P4kStarmapObjectDto record,
      @NotNull UUID guid,
      @NotNull Map<UUID, StarmapObject> byGuid,
      @NotNull P4kImportContext context) {
    StarmapObject row = byGuid.get(guid);
    if (row == null) {
      row = new StarmapObject();
      row.setP4kUuid(guid);
      copy(record, row);
      byGuid.put(guid, row);
      context.getReport().added(KIND, record);
      if (context.isApply()) {
        repository.save(row);
      }
      return new Pending(record, row, null);
    }
    StarmapObject target = row;
    P4kRowWriter writer = new P4kRowWriter(context, KIND, record);
    writer.mirror("class_name", target.getClassName(), record.className(), target::setClassName);
    writer.mirror(
        "record_path", target.getRecordPath(), record.recordPath(), target::setRecordPath);
    writer.mirror("type", target.getType(), record.type(), target::setType);
    writer.mirror("star_system", target.getStarSystem(), record.system(), target::setStarSystem);
    writer.mirror(
        "hide_in_starmap",
        target.getHideInStarmap(),
        record.hideInStarmap(),
        target::setHideInStarmap);
    writer.mirror("position_x", target.getPositionX(), record.coordinate(0), target::setPositionX);
    writer.mirror("position_y", target.getPositionY(), record.coordinate(1), target::setPositionY);
    writer.mirror("position_z", target.getPositionZ(), record.coordinate(2), target::setPositionZ);
    writer.leadGameText(
        "name", target.getName(), P4kLocalisedText.enOf(record.name()), target::setName);
    writer.leadGameText(
        "name_de", target.getNameDe(), P4kFields.germanName(record.name()), target::setNameDe);
    return new Pending(record, target, writer);
  }

  /**
   * Sets the parent of an object from the run, logging a changed parent of a matched one.
   *
   * @param pending the object of the run
   * @param guid its GUID
   * @param byGuid every object by GUID
   * @param parents every parent GUID by object GUID, updated
   * @param context the run
   */
  private static void linkParent(
      @NotNull Pending pending,
      @NotNull UUID guid,
      @NotNull Map<UUID, StarmapObject> byGuid,
      @NotNull Map<UUID, UUID> parents,
      @NotNull P4kImportContext context) {
    UUID incoming = pending.record().parsedParentGuid();
    StarmapObject parent = incoming == null ? null : byGuid.get(incoming);
    UUID parentGuid = parent == null ? null : incoming;
    UUID current = parents.get(guid);
    if (parentGuid == null) {
      parents.remove(guid);
    } else {
      parents.put(guid, parentGuid);
    }
    StarmapObject row = pending.row();
    if (pending.writer() == null) {
      if (context.isApply()) {
        row.setParentId(idOf(parent));
      }
      return;
    }
    pending.writer().mirror("parent_id", current, parentGuid, _ -> row.setParentId(idOf(parent)));
  }

  /**
   * Returns the id of an object.
   *
   * @param object the object, or {@code null}
   * @return its id, or {@code null}
   */
  @Nullable
  private static UUID idOf(@Nullable StarmapObject object) {
    return object == null ? null : object.getId();
  }

  /**
   * Copies a record's values onto an added object.
   *
   * @param record the record
   * @param row the added object
   */
  private static void copy(@NotNull P4kStarmapObjectDto record, @NotNull StarmapObject row) {
    row.setClassName(record.className());
    row.setRecordPath(record.recordPath());
    row.setType(record.type());
    row.setStarSystem(record.system());
    row.setHideInStarmap(record.hideInStarmap());
    row.setPositionX(record.coordinate(0));
    row.setPositionY(record.coordinate(1));
    row.setPositionZ(record.coordinate(2));
    row.setName(P4kLocalisedText.enOf(record.name()));
    row.setNameDe(P4kFields.germanName(record.name()));
  }

  /**
   * An object of the run.
   *
   * @param record the record
   * @param row the stored or added object
   * @param writer the writer of a matched object, {@code null} for an added one
   */
  private record Pending(
      @NotNull P4kStarmapObjectDto record,
      @NotNull StarmapObject row,
      @Nullable P4kRowWriter writer) {}
}
