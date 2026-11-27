package com.abnamro.delivery.order.fixture;

import com.abnamro.delivery.order.domain.DeliveryType;
import com.abnamro.delivery.order.domain.OrderRequest;
import com.abnamro.delivery.order.domain.Party;
import com.abnamro.delivery.order.domain.PostalCode;

/** Fictional test data only: no real names or addresses. */
public final class TestOrders {

    private TestOrders() {
    }

    public static OrderRequest orderRequest(String recipientCountry) {
        return new OrderRequest("CUSTOMER-TEST-001",
                new Party("Test Sender", PostalCode.of("1012AB"), "NL"),
                new Party("Test Recipient", PostalCode.of("5611EM"), recipientCountry),
                2750, DeliveryType.NEXT_DAY);
    }

    public static OrderRequest orderRequest() {
        return orderRequest("NL");
    }

    /** JSON body for POST /api/v1/delivery-orders. */
    public static String requestJson(String senderPostalCode, long weightInGrams, String recipientCountry) {
        return """
                {"customerReference":"CUSTOMER-TEST-001",
                 "sender":{"name":"Test Sender","postalCode":"%s","countryCode":"NL"},
                 "recipient":{"name":"Test Recipient","postalCode":"5611EM","countryCode":"%s"},
                 "parcel":{"weightInGrams":%d},
                 "deliveryType":"NEXT_DAY"}
                """.formatted(senderPostalCode, recipientCountry, weightInGrams);
    }

    public static String requestJson() {
        return requestJson("1012AB", 2750, "NL");
    }
}
