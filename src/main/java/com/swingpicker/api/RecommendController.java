package com.swingpicker.api;

import com.swingpicker.core.RecommendationService;
import com.swingpicker.core.momentum.MomentumResult;
import com.swingpicker.core.momentum.MomentumService;
import com.swingpicker.domain.Recommendation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RecommendController {

    public record RecommendRequest(@Positive double amount) {
    }

    /** Both strategies for the same amount; pick one, the amount isn't split between them. */
    public record RecommendResponse(double amount, MomentumResult momentum, Recommendation swing) {
    }

    private final RecommendationService swing;
    private final MomentumService momentum;

    public RecommendController(RecommendationService swing, MomentumService momentum) {
        this.swing = swing;
        this.momentum = momentum;
    }

    /** POST /api/recommend {"amount": 10000} */
    @PostMapping("/recommend")
    public RecommendResponse recommend(@Valid @RequestBody RecommendRequest request) {
        Recommendation s = swing.recommend(request.amount());       // loads and caches all bars in parallel
        MomentumResult m = momentum.recommend(request.amount());    // then reuses them
        return new RecommendResponse(request.amount(), m, s);
    }
}
