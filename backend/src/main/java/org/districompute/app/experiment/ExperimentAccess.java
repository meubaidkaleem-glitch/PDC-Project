package org.districompute.app.experiment;

import org.districompute.app.api.ApiException;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExperimentRepository;
import org.districompute.app.security.CurrentUser;
import org.springframework.stereotype.Component;

/** Resolves experiments for the current user: owners and administrators only; others get 404 (no disclosure). */
@Component
public class ExperimentAccess {

    private final ExperimentRepository experiments;
    private final CurrentUser currentUser;

    ExperimentAccess(ExperimentRepository experiments, CurrentUser currentUser) {
        this.experiments = experiments;
        this.currentUser = currentUser;
    }

    /**
     * The experiment, if the current user may see it.
     *
     * @param id experiment id
     * @return the experiment
     * @throws ApiException 404 otherwise
     */
    public Experiment require(String id) {
        Experiment experiment = experiments.findById(id).orElseThrow(() -> ApiException.notFound("Experiment", id));
        if (!currentUser.isAdmin() && !experiment.getOwner().getId().equals(currentUser.require().getId())) {
            throw ApiException.notFound("Experiment", id);
        }
        return experiment;
    }
}
