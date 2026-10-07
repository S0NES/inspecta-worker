package br.com.vistoria.worker.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Eventos {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Eventos() {}

    public static JsonNode parse(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Evento invalido: " + new String(body), e);
        }
    }
}
