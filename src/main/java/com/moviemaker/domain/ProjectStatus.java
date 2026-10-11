package com.moviemaker.domain;

public enum ProjectStatus {
    QUEUED, EXTRACTING, PLANNING, RENDERING, NARRATING, ASSEMBLING, COMPLETED, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
