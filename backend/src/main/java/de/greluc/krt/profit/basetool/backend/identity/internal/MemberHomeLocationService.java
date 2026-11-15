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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.identity.api.HomeLocationPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a member's profile setting "my home location" (REQ-PATCH-030…032): the member
 * chooses from the curated home locations, and a patch-change relocation overwrites the choice.
 * Every change is audited as {@link AuditEventType#HOME_LOCATION_PREFERENCE_SET}.
 */
@Service
@RequiredArgsConstructor
public class MemberHomeLocationService implements HomeLocationPreference {

  private final MemberHomeLocationRepository repository;
  private final LocationRepository locations;
  private final UserRepository users;
  private final AuditRecorder auditRecorder;

  /**
   * Reads a member's setting.
   *
   * @param member the member
   * @return the chosen home location and the version to echo back; no location at version 0 when
   *     the member made no choice
   */
  @Transactional(readOnly = true)
  public @NotNull MyHomeLocationResponse homeLocationOf(@NotNull UUID member) {
    return repository
        .findById(member)
        .map(row -> new MyHomeLocationResponse(row.getLocationId(), row.getVersion()))
        .orElseGet(() -> new MyHomeLocationResponse(null, 0L));
  }

  /**
   * Sets a member's home location from the profile; it only changes the forms' default and moves
   * nothing. Choosing the stored location again changes nothing.
   *
   * @param member the member
   * @param locationId the chosen home location
   * @param version the version the member last read, {@code 0} for a first choice
   * @return the stored setting with its new version
   * @throws NotFoundException when the member is unknown
   * @throws BadRequestException {@code error.profile.home_location_not_offered} when the location
   *     is not a curated home location the pickers offer
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull MyHomeLocationResponse choose(
      @NotNull UUID member, @NotNull UUID locationId, long version) {
    if (!users.existsById(member)) {
      throw new NotFoundException("User not found");
    }
    MyHomeLocationResponse current = homeLocationOf(member);
    OptimisticLock.checkRequired(current.version(), version, MemberHomeLocation.class, member);
    Location location = requireCurated(locationId);
    if (locationId.equals(current.homeLocationId())) {
      return current;
    }
    int written =
        version == 0
            ? repository.insertFirst(member, locationId)
            : repository.updateAt(member, locationId, version);
    if (written == 0) {
      throw new ObjectOptimisticLockingFailureException(MemberHomeLocation.class, member);
    }
    recordChange(member, location.getName(), HomeLocationPreferenceSource.PROFILE);
    return new MyHomeLocationResponse(locationId, version + 1);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void followRelocation(@NotNull UUID member, @NotNull UUID locationId) {
    if (repository.follow(member, locationId) > 0) {
      recordChange(
          member,
          locations.getReferenceById(locationId).getName(),
          HomeLocationPreferenceSource.RELOCATION);
    }
  }

  /**
   * Resolves a location the member may choose.
   *
   * @param locationId the location
   * @return the location
   * @throws BadRequestException {@code error.profile.home_location_not_offered} when it is unknown,
   *     not a home location, hidden or reference-only
   */
  private @NotNull Location requireCurated(@NotNull UUID locationId) {
    return locations
        .findById(locationId)
        .filter(OfferedLocations::isOffered)
        .filter(location -> Boolean.TRUE.equals(location.getHomeLocation()))
        .orElseThrow(() -> new BadRequestException("error.profile.home_location_not_offered"));
  }

  /**
   * Records a change of the setting; the subject and the target are the member.
   *
   * @param member the member
   * @param locationName the name of the new home location
   * @param source what changed it
   */
  private void recordChange(
      @NotNull UUID member,
      @NotNull String locationName,
      @NotNull HomeLocationPreferenceSource source) {
    auditRecorder.record(
        AuditEventType.HOME_LOCATION_PREFERENCE_SET,
        member,
        null,
        member,
        AuditDetails.of("location", locationName).with("source", source));
  }
}
