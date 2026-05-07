package com.nookx.ingester.web;

import com.nookx.ingester.config.IngesterProperties.IngestTargetConfig;
import com.nookx.ingester.domain.enumeration.PushStatus;
import com.nookx.ingester.ingest.api.IngestTarget;
import com.nookx.ingester.ingest.api.NormalizedPayload;
import com.nookx.ingester.ingest.runtime.IngestTargetRegistry;
import com.nookx.ingester.pipeline.runner.JobDispatcher;
import com.nookx.ingester.pipeline.service.ParsedPayloadService;
import com.nookx.ingester.repository.ParsedPayloadRepository;
import com.nookx.ingester.web.dto.IngestTargetView;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class IngestTargetController {

    private final IngestTargetRegistry ingestTargetRegistry;
    private final ParsedPayloadRepository parsedPayloadRepository;
    private final ParsedPayloadService parsedPayloadService;
    private final JobDispatcher jobDispatcher;

    @GetMapping("/ingest-targets")
    public String list(final Model model) {
        model.addAttribute("targets", buildViews());
        model.addAttribute("pageTitle", "Ingest targets");
        return "ingest_targets/list";
    }

    @PostMapping("/ingest-targets/{code}/push/run")
    public String runPush(
        @PathVariable("code") final String code,
        @RequestParam(name = "limit", defaultValue = "0") final int limit,
        final Model model
    ) {
        jobDispatcher.dispatchPushForTarget(code, limit);
        return refreshFragment(code, model, "Push dispatched for " + code);
    }

    @PostMapping("/ingest-targets/{code}/errors/reset")
    public String resetErrors(@PathVariable("code") final String code, final Model model) {
        final int affected = parsedPayloadService.resetPushErrors(code);
        return refreshFragment(code, model, "Reset push errors: " + affected + " row(s)");
    }

    private String refreshFragment(final String code, final Model model, final String message) {
        model.addAttribute("target", findView(code));
        model.addAttribute("flashMessage", message);
        return "ingest_targets/_row :: row(${target}, ${flashMessage})";
    }

    private IngestTargetView findView(final String code) {
        for (final IngestTargetView view : buildViews()) {
            if (view.code().equals(code)) {
                return view;
            }
        }
        return null;
    }

    private List<IngestTargetView> buildViews() {
        final List<IngestTargetView> views = new ArrayList<>();
        for (final IngestTarget<? extends NormalizedPayload> target : ingestTargetRegistry.all()) {
            final IngestTargetConfig config = ingestTargetRegistry.configOf(target.code());
            views.add(IngestTargetView.builder()
                .withCode(target.code())
                .withPayloadType(target.payloadType().getSimpleName())
                .withEnabled(config == null || config.isEnabled())
                .withPushCron(config != null ? config.getPushCron() : null)
                .withBaseUrl(config != null ? config.getBaseUrl() : null)
                .withMaxBatchSize(config != null ? config.getMaxBatchSize() : 0)
                .withPendingPush(parsedPayloadRepository.countByIngestTargetCodeAndPushStatus(target.code(), PushStatus.PENDING))
                .withPushedCount(parsedPayloadRepository.countByIngestTargetCodeAndPushStatus(target.code(), PushStatus.PUSHED))
                .withFailedPush(parsedPayloadRepository.countByIngestTargetCodeAndPushStatus(target.code(), PushStatus.FAILED))
                .withAlreadyExists(parsedPayloadRepository.countByIngestTargetCodeAndPushStatus(target.code(), PushStatus.ALREADY_EXISTS))
                .build());
        }
        return views;
    }
}
