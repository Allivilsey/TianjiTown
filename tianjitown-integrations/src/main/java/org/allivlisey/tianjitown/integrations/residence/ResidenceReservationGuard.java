package org.allivlisey.tianjitown.integrations.residence;

import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.event.ResidenceAreaAddEvent;
import com.bekvon.bukkit.residence.event.ResidenceSizeChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceSubzoneCreationEvent;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Reservations impose no gameplay flags; only Residence geometry mutations are blocked. */
public final class ResidenceReservationGuard implements Listener {
    private final ResidenceLandProtectionService protection;

    public ResidenceReservationGuard(ResidenceLandProtectionService protection) {
        this.protection = protection;
    }

    private boolean blocked(CuboidArea area) {
        return !protection.internalMutation() && (!protection.reservationsReady() || protection.reservationCollision(area, null) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreate(ResidenceCreationEvent event) {
        if (blocked(event.getPhysicalArea())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAdd(ResidenceAreaAddEvent event) {
        if (blocked(event.getPhysicalArea())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResize(ResidenceSizeChangeEvent event) {
        if (blocked(event.getNewArea())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSubzone(ResidenceSubzoneCreationEvent event) {
        if (blocked(event.getPhysicalArea())) event.setCancelled(true);
    }
}
