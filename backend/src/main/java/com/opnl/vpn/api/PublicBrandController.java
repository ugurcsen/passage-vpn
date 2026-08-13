package com.opnl.vpn.api;

import com.opnl.vpn.brand.BrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated branding endpoint consumed by the login page and theme bootstrap. */
@RestController
@RequestMapping("/api/public/brand")
@Tag(name = "Public", description = "Unauthenticated endpoints")
public class PublicBrandController {

  private final BrandService brandService;

  public PublicBrandController(BrandService brandService) {
    this.brandService = brandService;
  }

  @GetMapping
  @Operation(summary = "Effective brand configuration")
  public BrandDto brand() {
    return brandService.brand();
  }
}
