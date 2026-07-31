package com.interviewprep.orders.patterns.structural.bridge;

/**
 * WRONG — modeling "kind of notification" x "delivery channel" as a single
 * inheritance hierarchy produces one class PER COMBINATION, growing
 * multiplicatively as either axis grows.
 *
 * With just 2 notification kinds (standard, urgent) x 2 channels (email,
 * SMS), that's already 4 classes below. Add a third channel (push
 * notification) and you need 2 MORE classes (EmailX2 already exist, add
 * PushOrderNotification, PushUrgentOrderNotification) — every new channel
 * multiplies across every existing kind, and every new kind multiplies
 * across every existing channel. This is "class explosion," and it's the
 * defining symptom Bridge is designed to eliminate.
 *
 * Each class below is nearly identical to its siblings except for which
 * channel-specific send call it makes — that duplication is the tell that
 * two independent axes of variation (kind, channel) were collapsed into one
 * hierarchy instead of being kept separate.
 *
 * (Kept as nested static classes in one file, rather than four top-level
 * files, purely to keep this "wrong" example's combinatorial-explosion
 * point visible at a glance — imagine each of these as its own file in a
 * real codebase, which is the actual, worse reality this causes.)
 */
public class NaiveNotificationClassExplosion {

    public static class EmailOrderNotification {
        public void notifyCustomer(String recipient, String orderId) {
            System.out.println("EMAIL to " + recipient + ": Your order " + orderId + " was placed.");
        }
    }

    public static class SmsOrderNotification {
        public void notifyCustomer(String recipient, String orderId) {
            System.out.println("SMS to " + recipient + ": Your order " + orderId + " was placed.");
        }
    }

    // "Urgent" is a different KIND of notification (different message
    // framing / possibly retried delivery) — modeled here as a SEPARATE
    // subclass pair per channel, instead of varying independently.
    public static class EmailUrgentOrderNotification {
        public void notifyCustomer(String recipient, String orderId) {
            System.out.println("EMAIL [URGENT] to " + recipient + ": Action needed on order " + orderId + "!");
        }
    }

    public static class SmsUrgentOrderNotification {
        public void notifyCustomer(String recipient, String orderId) {
            System.out.println("SMS [URGENT] to " + recipient + ": Action needed on order " + orderId + "!");
        }
    }

    // A THIRD channel (push) would require TWO more classes just to cover
    // the kinds that already exist — this is the multiplicative growth Bridge
    // avoids by keeping "kind" and "channel" as two separate, composable
    // hierarchies. See OrderNotification / UrgentOrderNotification (abstraction)
    // and NotificationSender / EmailNotificationSender / SmsNotificationSender
    // (implementor) for the fix.
}
