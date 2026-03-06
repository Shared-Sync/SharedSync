package com.sharedsync.shared.util;


import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class LocalTime24Deserializer extends JsonDeserializer<LocalTime> {
    @Override
    public LocalTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String time = p.getText();
        if (time.startsWith("24:")) {
            return LocalTime.of(23, 59, 59); // 23:59:59로 변환
        }
        return LocalTime.parse(time, DateTimeFormatter.ISO_LOCAL_TIME);
    }
}