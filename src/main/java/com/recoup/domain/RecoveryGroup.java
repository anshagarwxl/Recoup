package com.recoup.domain;

/**
 * Designates whether a payment case receives active recovery intervention
 * or is held out as a no-intervention baseline for computing incremental lift.
 */
public enum RecoveryGroup {

    /** Active recovery intervention is applied. Performance is measured against this group. */
    TREATMENT,

    /**
     * No intervention applied. Held out to establish a natural-recovery baseline.
     * Incremental recovery lift = treatment net recovered - control natural recovery.
     */
    CONTROL
}
