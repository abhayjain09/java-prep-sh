package com.interviewprep.orders.patterns.structural.composite;

import com.interviewprep.orders.domain.Product;

import java.math.BigDecimal;
import java.util.List;

/**
 * WRONG — treats "a single product" and "a bundle of products" as two
 * unrelated types with NO common interface, forcing every piece of logic
 * that touches either one to branch with {@code instanceof} (or, as done
 * literally below, keep two entirely separate collections and write the
 * branch twice — an even more common real-world variant).
 *
 * WHY THIS DOESN'T SCALE: every new operation over "things in an order"
 * (pricing, tax, shipping weight, receipt line rendering) needs its OWN
 * instanceof-laden method, or its own pair of methods (one for products, one
 * for bundles) that the caller must remember to call both of and sum. Add a
 * THIRD kind of sellable thing (say, a nested bundle-of-bundles for a
 * "super kit") and every one of these methods needs a new branch — this is
 * an Open/Closed Principle violation (see SOLID.md): adding a new "shape" of
 * sellable thing means editing existing, already-shipped pricing logic
 * instead of just adding a new class.
 *
 * Contrast with {@link ProductBundle#price()}, which needs no instanceof at
 * all because leaf and composite share the {@link OrderComponent} interface,
 * and adding a new implementation of that interface requires zero changes
 * to existing pricing code.
 */
public class NaiveBundlePricing {

    public BigDecimal priceOfEverything(List<Product> standaloneProducts,
                                         List<List<Product>> bundles,
                                         BigDecimal perBundleDiscount) {
        BigDecimal total = BigDecimal.ZERO;

        // Branch 1: plain products.
        for (Product product : standaloneProducts) {
            total = total.add(product.price());
        }

        // Branch 2: bundles — a SEPARATE loop shape, duplicating the summing
        // logic, plus bundle-specific discount logic bolted on inline.
        for (List<Product> bundle : bundles) {
            BigDecimal bundleTotal = BigDecimal.ZERO;
            for (Product product : bundle) {
                bundleTotal = bundleTotal.add(product.price());
            }
            bundleTotal = bundleTotal.subtract(perBundleDiscount).max(BigDecimal.ZERO);
            total = total.add(bundleTotal);
        }

        // A "bundle of bundles" isn't even representable with this data
        // shape (List<List<Product>> has no way to nest another level) —
        // the naive design can't even express what ProductBundle.add(other
        // ProductBundle) supports for free via recursion.
        return total;
    }
}
