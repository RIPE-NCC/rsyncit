package net.ripe.rpki.rsyncit.service;

import net.ripe.rpki.rsyncit.rrdp.State;

public class HealthController {

    private final SyncService syncService;

    public HealthController(SyncService syncService) {
        this.syncService = syncService;
    }

    public State.RrdpState getStatus() {
        return syncService.getState().getRrdpState();
    }

}
