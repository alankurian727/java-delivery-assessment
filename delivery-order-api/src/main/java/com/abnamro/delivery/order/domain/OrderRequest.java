package com.abnamro.delivery.order.domain;

/** A validated, normalised create-order request. */
public record OrderRequest(String customerReference, Party sender, Party recipient, long weightInGrams,
                           DeliveryType deliveryType) {

    public boolean isWithinNetherlands() {
        return "NL".equals(sender.countryCode()) && "NL".equals(recipient.countryCode());
    }
}
