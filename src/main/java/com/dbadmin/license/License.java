package com.dbadmin.license;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Who a license is for, which kind it is and when it ends. {@code expires} is the last day it is valid
 * (inclusive); null for a lifetime license. Turned into a key by {@link LicenseKey}.
 */
public record License(Type type, String name, LocalDate issued, LocalDate expires) {

    public enum Type {
        TRIAL("Trial"), MONTHLY("Monthly"), YEARLY("Yearly"), LIFETIME("Lifetime");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The last valid day of a license of this type starting on {@code start}; null for lifetime. */
        public LocalDate lastDay(LocalDate start) {
            return switch (this) {
                case TRIAL -> start.plusDays(TRIAL_DAYS - 1);
                case MONTHLY -> start.plusMonths(1).minusDays(1);
                case YEARLY -> start.plusYears(1).minusDays(1);
                case LIFETIME -> null;
            };
        }
    }

    public static final int TRIAL_DAYS = 30;
    /** The footer warns this many days before the end. */
    public static final int WARN_DAYS = 7;
    /** Paid licenses (monthly, yearly) keep working this many days after the end; a trial does not. */
    public static final int GRACE_DAYS = 7;

    public enum Status { VALID, EXPIRING, GRACE, EXPIRED }

    /** Where this license stands on {@code today}. */
    public Status status(LocalDate today) {
        if (expires == null) return Status.VALID;
        long left = ChronoUnit.DAYS.between(today, expires);
        if (left >= WARN_DAYS) return Status.VALID;
        if (left >= 0) return Status.EXPIRING;
        boolean paid = type == Type.MONTHLY || type == Type.YEARLY;
        return paid && -left <= GRACE_DAYS ? Status.GRACE : Status.EXPIRED;
    }

    /** Whether licensed features (Compare) work on {@code today}. */
    public boolean allows(LocalDate today) {
        return status(today) != Status.EXPIRED;
    }

    /** Days until the end (0 on the last day, negative after it); Long.MAX_VALUE for lifetime. */
    public long daysLeft(LocalDate today) {
        return expires == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(today, expires);
    }
}
