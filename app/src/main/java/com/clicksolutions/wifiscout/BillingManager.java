package com.clicksolutions.wifiscout;

import android.app.Activity;

import androidx.annotation.NonNull;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;

import java.util.Collections;
import java.util.List;

/**
 * Google Play in-app purchase of the WiFi Scout license.
 *
 * The product is sold as a consumable: entitlement (with its 3-year expiry)
 * lives in OUR Firestore, and consuming lets the customer buy again when the
 * license period ends.
 */
public class BillingManager implements PurchasesUpdatedListener {

    public static final String PRODUCT_ID = "license_3yr";

    public interface Listener {
        /** Payment confirmed by Google — grant the license now. */
        void onPurchased(String orderId);
        void onPurchaseError(String message);
    }

    private final BillingClient client;
    private final Listener listener;
    private ProductDetails product;

    public BillingManager(Activity activity, Listener listener) {
        this.listener = listener;
        client = BillingClient.newBuilder(activity)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts().build())
                .build();
        client.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(@NonNull BillingResult r) {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) queryProduct();
            }
            @Override public void onBillingServiceDisconnected() { /* retried on next launch */ }
        });
    }

    private void queryProduct() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(
                        QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(PRODUCT_ID)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build()))
                .build();
        client.queryProductDetailsAsync(params, (r, result) -> {
            List<ProductDetails> list = result.getProductDetailsList();
            if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && !list.isEmpty())
                product = list.get(0);
        });
    }

    /** True when Google Play is reachable and the product exists. */
    public boolean isReady() { return product != null; }

    /** Localized price ("$19.90") straight from the store, or null before ready. */
    public String getPrice() {
        return product != null && product.getOneTimePurchaseOfferDetails() != null
                ? product.getOneTimePurchaseOfferDetails().getFormattedPrice() : null;
    }

    public void launchPurchase(Activity activity) {
        if (product == null) {
            listener.onPurchaseError("Google Play billing isn't available right now.");
            return;
        }
        BillingFlowParams flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(product)
                                .build()))
                .build();
        client.launchBillingFlow(activity, flow);
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult r, List<Purchase> purchases) {
        if (r.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) return;
        if (r.getResponseCode() != BillingClient.BillingResponseCode.OK || purchases == null) {
            listener.onPurchaseError("Purchase failed ("
                    + r.getResponseCode() + ") — you were not charged twice; try again.");
            return;
        }
        for (Purchase p : purchases) {
            if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
            String orderId = p.getOrderId() != null ? p.getOrderId() : p.getPurchaseToken();
            listener.onPurchased(orderId);
            // consume so the customer can buy again after the license expires
            client.consumeAsync(ConsumeParams.newBuilder()
                    .setPurchaseToken(p.getPurchaseToken()).build(), (cr, tok) -> {});
        }
    }
}
