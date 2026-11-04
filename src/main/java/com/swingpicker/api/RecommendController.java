package com.swingpicker.api;

import com.swingpicker.core.LiveSession;
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

import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api")
public class RecommendController {

    public record RecommendRequest(@Positive double amount) {
    }

    /** Whether prices are live (market open) or picks are for the next session, in plain words. */
    public record Session(boolean live, String checkedAt, String message) {
    }

    /** Both strategies for the same amount; pick one, the amount isn't split between them. */
    public record RecommendResponse(double amount, Session session, MomentumResult momentum, Recommendation swing) {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a");

    private final RecommendationService swing;
    private final MomentumService momentum;
    private final LiveSession liveSession;

    public RecommendController(RecommendationService swing, MomentumService momentum, LiveSession liveSession) {
        this.swing = swing;
        this.momentum = momentum;
        this.liveSession = liveSession;
    }

    /** POST /api/recommend {"amount": 10000} */
    @PostMapping("/recommend")
    public RecommendResponse recommend(@Valid @RequestBody RecommendRequest request) {
        boolean live = liveSession.isOpen();
        String at = liveSession.now().format(WHEN) + " IST";
        Recommendation s = swing.recommend(request.amount());       // loads and caches all bars in parallel
        MomentumResult m = momentum.recommend(request.amount());    // then reuses them
        String message = live
                ? "Market is open: everything below uses live prices (refreshed every ~3 minutes). Momentum picks can be "
                  + "bought now. Swing signals are provisional until the 3:30 close: buy after about 3:15 if a stock still "
                  + "shows here, with the same stop and target."
                : "Market is closed: everything below uses the last completed session and is for the next session's open.";
        return new RecommendResponse(request.amount(), new Session(live, at, message), m, s);
    }
}
