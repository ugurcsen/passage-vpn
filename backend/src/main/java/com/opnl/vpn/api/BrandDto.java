package com.opnl.vpn.api;

/** Public brand payload used to theme the UI before authentication. */
public record BrandDto(String name, String primaryColor, String footer, String logoUrl) {}
