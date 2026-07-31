package com.interviewprep.orders.patterns.behavioral.interpreter;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;

/**
 * WRONG — every new discount-eligibility RULE COMBINATION is hardcoded as
 * its own nested if/else (or boolean expression) directly in Java, inside a
 * method that has to be edited every time marketing invents a new
 * combination of conditions.
 *
 * WHY THIS IS A PROBLEM:
 * 1. COMBINATIONS EXPLODE: "VIP customer AND spent > $100" already needs a
 *    dedicated branch below; "VIP customer AND spent > $100, OR has a
 *    coupon code" needs another; every new marketing campaign idea
 *    ("first-time customer OR referred-by-VIP") means a NEW hand-written
 *    boolean expression, and there's no way to reuse the pieces ("is VIP,"
 *    "spent > $100") across rules except copy-pasting the sub-condition
 *    each time.
 * 2. RULES AREN'T DATA: because each rule is Java code, defining a new
 *    promotional rule requires a code change and a deploy — marketing/ops
 *    teams can't configure a new rule combination themselves, and rules
 *    can't be stored/edited in a database or admin UI.
 * 3. NO REUSE OF SUB-CONDITIONS: "spent > $100" is checked identically in
 *    both branches below — a small example, but in a real rules engine with
 *    dozens of promotions, this duplication compounds badly.
 *
 * Compare with {@link AndExpression}/{@link OrExpression} wrapping
 * {@link SpendOverExpression}/{@link CustomerEmailDomainExpression}: each
 * primitive condition is ONE small reusable class, and combinations are
 * built by COMPOSING objects (even, in principle, from data — see the
 * Javadoc on {@link DiscountRuleExpression}) rather than writing new Java
 * conditionals for every new combination.
 */
public class NaiveNestedConditionals {

    public boolean isEligibleForVipSpendPromo(Order order) {
        boolean isVip = order.customer().email().endsWith("@vip-corp.com");
        boolean spentEnough = order.totalAmount().compareTo(new BigDecimal("100")) > 0;
        return isVip && spentEnough;
    }

    // A SECOND, similar-but-different rule — re-checks "spent enough" with
    // the same hardcoded threshold, duplicated rather than reused, and adds
    // yet another nested condition of its own.
    public boolean isEligibleForVipOrBigSpenderPromo(Order order) {
        boolean isVip = order.customer().email().endsWith("@vip-corp.com");
        boolean spentEnough = order.totalAmount().compareTo(new BigDecimal("100")) > 0;
        boolean spentALot = order.totalAmount().compareTo(new BigDecimal("500")) > 0;
        return (isVip && spentEnough) || spentALot;
        // A THIRD promo combining these differently means a THIRD method,
        // re-deriving the same underlying conditions yet again.
    }
}
