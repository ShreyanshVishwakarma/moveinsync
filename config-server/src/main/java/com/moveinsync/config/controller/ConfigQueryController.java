package com.moveinsync.config.controller;

import com.moveinsync.config.domain.TenantContext;
import com.moveinsync.config.dto.ExplainResponse;
import com.moveinsync.config.dto.ResolveResponse;
import com.moveinsync.config.dto.SnapshotResponse;
import com.moveinsync.config.dto.SyncResponse;
import com.moveinsync.config.service.ConfigQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read endpoints. The production read path is the client SDK's local snapshot; these
 * endpoints exist for bootstrap, gap recovery, explain mode and observability.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigQueryController {

    private final ConfigQueryService queryService;

    public ConfigQueryController(ConfigQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/version")
    public Map<String, Long> version() {
        return Map.of("version", queryService.currentVersion());
    }

    @GetMapping("/resolve")
    public ResolveResponse resolve(@RequestParam String key,
                                   @RequestParam(required = false) String clientId,
                                   @RequestParam(required = false) String officeId,
                                   @RequestParam(required = false) String shiftId) {
        return queryService.resolve(key, new TenantContext(clientId, officeId, shiftId));
    }

    @GetMapping("/explain")
    public ExplainResponse explain(@RequestParam String key,
                                   @RequestParam(required = false) String clientId,
                                   @RequestParam(required = false) String officeId,
                                   @RequestParam(required = false) String shiftId) {
        return queryService.explain(key, new TenantContext(clientId, officeId, shiftId));
    }

    @GetMapping("/snapshot")
    public SnapshotResponse snapshot() {
        return queryService.snapshot();
    }

    /** @param since 0 or omitted means "send everything"; otherwise a delta from that version. */
    @GetMapping("/sync")
    public SyncResponse sync(@RequestParam(defaultValue = "0") long since) {
        return queryService.sync(since);
    }
}
