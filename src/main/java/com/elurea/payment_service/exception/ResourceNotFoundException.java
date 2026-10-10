package com.elurea.payment_service.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        super("%s not found with id %s".formatted(resource, id));
    }
}
