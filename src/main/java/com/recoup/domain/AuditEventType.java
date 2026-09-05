package com.recoup.domain;

public enum AuditEventType {
    FAILURE_RECORDED,
    DIAGNOSED,
    PLAN_CREATED,
    ACTION_EXECUTED,
    RECOVERY_STOPPED,
    PAYMENT_RECOVERED,
    CONTROL_HOLD
}
