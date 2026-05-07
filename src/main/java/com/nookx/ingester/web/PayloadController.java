package com.nookx.ingester.web;

import com.nookx.ingester.domain.ParsedAsset;
import com.nookx.ingester.domain.ParsedPayload;
import com.nookx.ingester.domain.enumeration.PushStatus;
import com.nookx.ingester.ingest.runtime.IngestTargetRegistry;
import com.nookx.ingester.repository.ParsedAssetRepository;
import com.nookx.ingester.repository.ParsedPayloadQueryRepository;
import com.nookx.ingester.repository.ParsedPayloadRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class PayloadController {

    private static final int DEFAULT_LIMIT = 100;

    private final ParsedPayloadQueryRepository parsedPayloadQueryRepository;
    private final ParsedPayloadRepository parsedPayloadRepository;
    private final ParsedAssetRepository parsedAssetRepository;
    private final IngestTargetRegistry ingestTargetRegistry;

    @GetMapping("/payloads")
    public String list(
        @RequestParam(name = "target", required = false) final String ingestTargetCode,
        @RequestParam(name = "pushStatus", required = false) final PushStatus pushStatus,
        @RequestParam(name = "externalId", required = false) final String externalIdContains,
        @RequestParam(name = "limit", defaultValue = "100") final int limit,
        final Model model
    ) {
        final int safeLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        final List<ParsedPayload> payloads = parsedPayloadQueryRepository.search(
            ingestTargetCode,
            pushStatus,
            externalIdContains,
            safeLimit
        );
        model.addAttribute("payloads", payloads);
        model.addAttribute("filterTarget", ingestTargetCode);
        model.addAttribute("filterPushStatus", pushStatus);
        model.addAttribute("filterExternalId", externalIdContains);
        model.addAttribute("filterLimit", safeLimit);
        model.addAttribute("targetCodes", ingestTargetRegistry.indexedByCode().keySet());
        model.addAttribute("pushStatuses", PushStatus.values());
        model.addAttribute("pageTitle", "Payloads");
        return "payloads/list";
    }

    @GetMapping("/payloads/{id}")
    public String detail(@PathVariable("id") final Long id, final Model model) {
        final ParsedPayload payload = parsedPayloadRepository.findById(id).orElse(null);
        if (payload == null) {
            return "redirect:/payloads";
        }
        final List<ParsedAsset> assets = parsedAssetRepository.findByParsedPayloadOrderBySortOrderAsc(payload);
        model.addAttribute("payload", payload);
        model.addAttribute("assets", assets);
        model.addAttribute("pageTitle", "Payload " + payload.getId());
        return "payloads/detail";
    }

    @PostMapping("/payloads/{id}/re-push")
    @Transactional
    public String rePush(@PathVariable("id") final Long id) {
        final Optional<ParsedPayload> opt = parsedPayloadRepository.findById(id);
        opt.ifPresent(payload -> {
            payload.setPushStatus(PushStatus.PENDING);
            payload.setPushRetryCount(0);
            payload.setPushLastError(null);
            payload.setUpdatedAt(Instant.now());
            parsedPayloadRepository.save(payload);
        });
        return "redirect:/payloads/" + id;
    }
}
