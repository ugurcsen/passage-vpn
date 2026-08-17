package com.passagevpn.network;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.List;

/** Stores a list of strings as a JSON TEXT column (portable to PostgreSQL). */
@Converter
public class JsonListConverter implements AttributeConverter<List<String>, String> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String convertToDatabaseColumn(List<String> attribute) {
    try {
      return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize string list", e);
    }
  }

  @Override
  public List<String> convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isBlank()) {
      return new ArrayList<>();
    }
    try {
      return new ArrayList<>(MAPPER.readValue(dbData, new TypeReference<List<String>>() {}));
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }
}
