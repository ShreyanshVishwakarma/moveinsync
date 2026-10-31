package com.moveinsync.config.controller;

import com.moveinsync.config.domain.TenantContext;
import com.moveinsync.config.dto.ExplainResponse;
import com.moveinsync.config.dto.ResolveResponse;
import com.moveinsync.config.dto.SnapshotResponse;
import com.moveinsync.config.dto.SyncResponse;
import com.moveinsync.config.security.Actor;
import com.moveinsync.config.security.TenantGuard;
import com.moveinsync.config.service.ConfigQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read endpoints. The production read path is the client SDK's local snapshot; these
 * endpoints exist for bootstrap, gap recovery, explain mode and observability.
 *
 * <p>Every read is tenant-scoped: a caller can only name its own {@code clientId}, and
 * snapshots only contain that tenant's overrides.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigQueryController {

    private final ConfigQueryService queryService;
    private final TenantGuard tenantGuard;

    public ConfigQueryController(ConfigQueryService queryService, TenantGuard tenantGuard) {
        this.queryService = queryService;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping("/version")
    public Map<String, Long> version() {
        return Map.of("version", queryService.currentVersion());
    }

    @GetMapping("/resolve")
    public ResolveResponse resolve(@RequestParam String key,
                                   @RequestParam(required = false) String clientId,
                                   @RequestParam(required = false) String officeId,
                                   @RequestParam(required = false) String shiftId,
                                   @AuthenticationPrincipal Actor actor) {
        String scopedClient = tenantGuard.effectiveClientId(actor, clientId);
        return queryService.resolve(key, new TenantContext(scopedClient, officeId, shiftId));
    }

    @GetMapping("/explain")
    public ExplainResponse explain(@RequestParam String key,
                                   @RequestParam(required = false) String clientId,
                                   @RequestParam(required = false) String officeId,
                                   @RequestParam(required = false) String shiftId,
                                   @AuthenticationPrincipal Actor actor) {
        String scopedClient = tenantGuard.effectiveClientId(actor, clientId);
        return queryService.explain(key, new TenantContext(scopedClient, officeId, shiftId));
    }

    @GetMapping("/snapshot")
    public SnapshotResponse snapshot(@AuthenticationPrincipal Actor actor) {
        return queryService.snapshot(actor);
    }

    /** @param since 0 or omitted means "send everything"; otherwise a delta from that version. */
    @GetMapping("/sync")
    public SyncResponse sync(@RequestParam(defaultValue = "0") long since,
                             @AuthenticationPrincipal Actor actor) {
        return queryService.sync(since, actor);
    }
}
