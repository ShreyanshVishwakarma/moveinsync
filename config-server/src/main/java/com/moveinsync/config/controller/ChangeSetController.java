package com.moveinsync.config.controller;

import com.moveinsync.config.dto.ChangeSetResponse;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.VersionSummary;
import com.moveinsync.config.security.Actor;
import com.moveinsync.config.service.ConfigChangeService;
import com.moveinsync.config.service.ConfigQueryService;
import com.moveinsync.config.service.PromotionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/change-sets")
public class ChangeSetController {

    private final ConfigChangeService changeService;
    private final ConfigQueryService queryService;
    private final PromotionService promotionService;

    public ChangeSetController(ConfigChangeService changeService,
                               ConfigQueryService queryService,
                               PromotionService promotionService) {
        this.changeService = changeService;
        this.queryService = queryService;
        this.promotionService = promotionService;
    }

    @PostMapping
    public ResponseEntity<ChangeSetResponse> commit(@Valid @RequestBody CommitChangeSetRequest request,
                                                    @AuthenticationPrincipal Actor actor) {
        ChangeSetResponse response = changeService.commit(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Widens a staged canary (office-level overrides) to every office of the owning
     * client, as a new version.
     */
    @PostMapping("/{version}/promote")
    public ResponseEntity<ChangeSetResponse> promote(@PathVariable long version,
                                                     @RequestParam(defaultValue = "false") boolean removeSourceOverrides,
                                                     @RequestParam(defaultValue = "operator") String author,
                                                     @AuthenticationPrincipal Actor actor) {
        ChangeSetResponse response = promotionService.promote(version, removeSourceOverrides, author, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<VersionSummary> list(@RequestParam(defaultValue = "50") int limit) {
        return queryService.listVersions(limit);
    }

    @GetMapping("/{version}")
    public ChangeSetResponse get(@PathVariable long version) {
        return queryService.getChangeSet(version);
    }
}
