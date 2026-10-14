/**
 * The purchasing module: the trade deals of the Handelsbeauftragten with external partners, paid
 * through purpose-bound bank requests (supply and purchasing plan section 5.1, rank 12).
 */
@ApplicationModule(
    allowedDependencies = {
      "admin::api",
      "audit::api",
      "bank::api",
      "blueprint::api",
      "catalogue::api",
      "hangar::api",
      "identity::api",
      "inventory::api",
      "joborder::api",
      "kernel",
      "livesync::api",
      "materialexchange::api",
      "mission::api",
      "notification::api",
      "operation::api",
      "orgunit::api",
      "platform::api",
      "refinery::api",
      "scope::api",
      "supply::api"
    })
package de.greluc.krt.profit.basetool.backend.purchasing;

import org.springframework.modulith.ApplicationModule;
