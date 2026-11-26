package com.abnamro.delivery.pricing.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.abnamro.delivery.pricing.domain.DomainValidationException;
import com.abnamro.delivery.pricing.service.PricingEvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Error paths the integration test cannot trigger with the real service.
 * The happy paths and validation errors are covered by PricingApiIT.
 */
@WebMvcTest(PricingController.class)
@Import(PricingApiMapperImpl.class)
class PricingControllerTest {

    private static final String URL = "/api/v1/pricing/evaluations";
    private static final String VALID_REQUEST = """
            {"requestId":"7cb82db3-fe9a-49e1-a0f9-fdeae2455869","originPostalCode":"1012AB",
             "destinationPostalCode":"5611EM","weightInGrams":2750,"deliveryType":"NEXT_DAY"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PricingEvaluationService pricingEvaluationService;

    @Test
    void returnsGenericInternalErrorWithoutInternalDetails() throws Exception {
        when(pricingEvaluationService.evaluate(any()))
                .thenThrow(new IllegalStateException("connection to pricing-db.internal:5432 failed"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.title").value("Internal error"))
                .andExpect(content().string(not(containsString("pricing-db.internal"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void returnsBadRequestForValuesRejectedByTheDomainWithoutTheExceptionMessage() throws Exception {
        when(pricingEvaluationService.evaluate(any()))
                .thenThrow(new DomainValidationException("Invalid Dutch postal code"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("A request value is not valid."))
                .andExpect(content().string(not(containsString("Invalid Dutch postal code"))));
    }

    @Test
    void treatsOtherIllegalArgumentsAsInternalErrors() throws Exception {
        when(pricingEvaluationService.evaluate(any()))
                .thenThrow(new IllegalArgumentException("Unexpected value 'EXPRESS' in com.example.Internal"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("EXPRESS"))))
                .andExpect(content().string(not(containsString("com.example"))));
    }
}
