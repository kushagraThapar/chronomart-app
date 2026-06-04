package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.Map;

@JsonInclude(Include.NON_NULL)
public record ErrorResponse(Error error) {

    @JsonInclude(Include.NON_NULL)
    public record Error(
        String code,
        String message,
        Integer status,
        Integer substatus,
        String activityId,
        String sdk,
        Map<String, Object> details
    ) {}

    public static ErrorResponse of(String code, String message, int status) {
        return new ErrorResponse(new Error(code, message, status, null, null, "java", null));
    }
}
