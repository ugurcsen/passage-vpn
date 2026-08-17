package com.passagevpn.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.passagevpn.brand.BrandService;
import com.passagevpn.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web-layer tests for the public brand endpoint. */
class PublicBrandControllerTest {

  private BrandService brandService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    brandService = mock(BrandService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new PublicBrandController(brandService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void returnsBrandPayload() throws Exception {
    when(brandService.brand())
        .thenReturn(
            new BrandDto("Acme VPN", "#ff8800", "Acme Corp", "https://example.com/logo.png"));

    mvc.perform(get("/api/public/brand"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Acme VPN"))
        .andExpect(jsonPath("$.primaryColor").value("#ff8800"))
        .andExpect(jsonPath("$.footer").value("Acme Corp"))
        .andExpect(jsonPath("$.logoUrl").value("https://example.com/logo.png"));
  }
}
