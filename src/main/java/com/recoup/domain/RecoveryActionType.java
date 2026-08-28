package com.recoup.domain;

public enum RecoveryActionType {
    RETRY_PAYMENT,
    SEND_UPI_REMINDER,
    SEND_PAYMENT_LINK,
    ESCALATE_TO_ACCOUNT_MANAGER,
    STOP_RECOVERY
}
