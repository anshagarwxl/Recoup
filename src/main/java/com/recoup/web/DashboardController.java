package com.recoup.web;

import com.recoup.domain.PaymentFailure;
import com.recoup.domain.RecoveryCase;
import com.recoup.generator.SyntheticDataGenerator;
import com.recoup.orchestrator.RecoveryMetrics;
import com.recoup.orchestrator.RecoveryOrchestrator;
import com.recoup.util.TimelineFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Web controller delivering the local recovery dashboard and transaction timeline audit views. */
@Controller
public class DashboardController {

    private final RecoveryOrchestrator orchestrator;
    private final SyntheticDataGenerator generator = new SyntheticDataGenerator();

    private List<RecoveryCase> activeCases;
    private RecoveryMetrics activeMetrics;
    private long currentSeed = 42L;
    private int currentSize = 125;

    public DashboardController(RecoveryOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
        refreshBatch(currentSize, currentSeed);
    }

    private synchronized void refreshBatch(int size, long seed) {
        this.currentSize = size;
        this.currentSeed = seed;
        List<PaymentFailure> failures = generator.generateBatch(size, seed);
        this.activeCases = orchestrator.processBatch(failures);
        this.activeMetrics = orchestrator.calculateMetrics(activeCases);
    }

    @GetMapping({"/", "/dashboard"})
    public String viewDashboard(
            @RequestParam(name = "seed", required = false) Long seed,
            @RequestParam(name = "size", required = false) Integer size,
            Model model) {

        if (seed != null || size != null) {
            long targetSeed = seed != null ? seed : currentSeed;
            int targetSize = size != null ? size : currentSize;
            refreshBatch(targetSize, targetSeed);
        }

        model.addAttribute("metrics", activeMetrics);
        model.addAttribute("cases", activeCases);
        model.addAttribute("currentSeed", currentSeed);
        model.addAttribute("currentSize", currentSize);
        return "dashboard";
    }

    @GetMapping("/api/case/{paymentId}")
    @ResponseBody
    public Optional<RecoveryCase> getCaseDetails(@PathVariable String paymentId) {
        return activeCases.stream()
                .filter(c -> c.paymentFailure().paymentId().equalsIgnoreCase(paymentId))
                .findFirst();
    }
}
