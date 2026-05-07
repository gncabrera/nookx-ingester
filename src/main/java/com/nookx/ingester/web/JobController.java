package com.nookx.ingester.web;

import com.nookx.ingester.config.IngesterProperties;
import com.nookx.ingester.domain.JobLog;
import com.nookx.ingester.domain.JobRun;
import com.nookx.ingester.job.JobRunService;
import com.nookx.ingester.repository.JobLogRepository;
import com.nookx.ingester.repository.JobRunRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
@RequiredArgsConstructor
public class JobController {

    private static final int RECENT_LIMIT = 200;

    private final JobRunService jobRunService;
    private final JobRunRepository jobRunRepository;
    private final JobLogRepository jobLogRepository;
    private final IngesterProperties properties;

    @GetMapping("/jobs")
    public String list(final Model model) {
        model.addAttribute("jobs", jobRunService.recent(RECENT_LIMIT));
        model.addAttribute("pageTitle", "Jobs");
        return "jobs/list";
    }

    @GetMapping("/jobs/{id}")
    public String detail(@PathVariable("id") final Long id, final Model model) {
        final JobRun run = jobRunRepository.findById(id).orElse(null);
        if (run == null) {
            return "redirect:/jobs";
        }
        final List<JobLog> logs = jobLogRepository.findByJobRunIdOrderByTsAsc(id);
        model.addAttribute("job", run);
        model.addAttribute("logs", logs);
        model.addAttribute("pollIntervalMs", properties.getDashboard().getPollIntervalMs());
        model.addAttribute("pageTitle", "Job " + id);
        return "jobs/detail";
    }

    @GetMapping("/jobs/{id}/logs")
    public String logsFragment(@PathVariable("id") final Long id, final Model model) {
        final JobRun run = jobRunRepository.findById(id).orElse(null);
        if (run == null) {
            return "redirect:/jobs";
        }
        final List<JobLog> logs = jobLogRepository.findByJobRunIdOrderByTsAsc(id);
        model.addAttribute("job", run);
        model.addAttribute("logs", logs);
        return "jobs/detail :: logs";
    }
}
