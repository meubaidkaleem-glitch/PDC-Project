package org.districompute.app.batch;

import jakarta.validation.Valid;
import java.util.List;
import org.districompute.app.batch.BatchViews.BatchView;
import org.districompute.app.batch.BatchViews.CreateBatch;
import org.districompute.app.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Batches of experiments run one after another ({@code docs/batches-and-templates.md}). */
@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {

    private final BatchService batches;
    private final CurrentUser currentUser;

    BatchController(BatchService batches, CurrentUser currentUser) {
        this.batches = batches;
        this.currentUser = currentUser;
    }

    /**
     * Submits a batch; nothing is created unless every item is valid.
     *
     * @param request the batch
     * @return 202 with the batch
     */
    @PostMapping
    public ResponseEntity<BatchView> create(@Valid @RequestBody CreateBatch request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(batches.create(request, currentUser.require(), currentUser.isAdmin()));
    }

    /**
     * Batches of the user (all for administrators).
     *
     * @param limit how many
     * @return batches, newest first
     */
    @GetMapping
    public List<BatchView> list(@RequestParam(defaultValue = "20") int limit) {
        return batches.list(currentUser.require(), currentUser.isAdmin(), limit);
    }

    /**
     * One batch.
     *
     * @param id batch
     * @return the batch with its experiments in order
     */
    @GetMapping("/{id}")
    public BatchView get(@PathVariable String id) {
        return batches.get(id, currentUser.require(), currentUser.isAdmin());
    }

    /**
     * Cancels the experiments of the batch that have not finished.
     *
     * @param id batch
     * @return the batch
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<BatchView> cancel(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(batches.cancel(id, currentUser.require(), currentUser.isAdmin()));
    }
}
