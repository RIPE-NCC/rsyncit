package net.ripe.rpki.rsyncit.service;

import net.ripe.rpki.rsyncit.rrdp.State;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final SyncService syncService;

    @Autowired
    public HealthController(SyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping(value = "status")
    public ResponseEntity<State.RrdpState> status() {
        final State.RrdpState rrdpState = syncService.getState().getRrdpState();
        if (rrdpState == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rrdpState);
    }

}
