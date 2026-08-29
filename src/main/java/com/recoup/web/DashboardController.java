package com.recoup.web;

import com.recoup.domain.PaymentFailure;
import com.recoup.domain.RecoveryCase;
import com.recoup.generator.SyntheticDataGenerator;
import com.recoup.orchestrator.RecoveryMetrics;
import com.recoup.orchestrator.RecoveryOrchestrator;
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
    private final SyntheticDataGenerator generator;

    private record DashboardState(long seed, int size, List<RecoveryCase> cases, RecoveryMetrics metrics) {}
    private volatile DashboardState state;

    public DashboardController(RecoveryOrchestrator orchestrator, SyntheticDataGenerator generator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
        refreshBatch(125, 42L);
    }

    private void refreshBatch(int size, long seed) {
        List<PaymentFailure> failures = generator.generateBatch(size, seed);
        List<RecoveryCase> activeCases = orchestrator.processBatch(failures);
        RecoveryMetrics activeMetrics = orchestrator.calculateMetrics(activeCases);
        this.state = new DashboardState(seed, size, activeCases, activeMetrics);
    }

    @GetMapping({"/", "/dashboard"})
    public String viewDashboard(
            @RequestParam(name = "seed", required = false) Long seed,
            @RequestParam(name = "size", required = false) Integer size,
            Model model) {

        DashboardState currentState = this.state;
        if (seed != null || size != null) {
            long targetSeed = seed != null ? seed : currentState.seed();
            int targetSize = size != null ? size : currentState.size();
            refreshBatch(targetSize, targetSeed);
            currentState = this.state;
        }

        model.addAttribute("metrics", currentState.metrics());
        model.addAttribute("cases", currentState.cases());
        model.addAttribute("currentSeed", currentState.seed());
        model.addAttribute("currentSize", currentState.size());
        return "dashboard";
    }

    @GetMapping("/api/case/{paymentId}")
    @ResponseBody
    public Optional<RecoveryCase> getCaseDetails(@PathVariable String paymentId) {
        return state.cases().stream()
                .filter(c -> c.paymentFailure().paymentId().equalsIgnoreCase(paymentId))
                .findFirst();
    }
}
