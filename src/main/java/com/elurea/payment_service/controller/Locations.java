package com.elurea.payment_service.controller;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Builds the Location header for 201 Created responses.
 */
final class Locations {

    private Locations() {
    }

    /** URI of the newly created resource, relative to the current collection URL. */
    static URI of(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(id)
                .toUri();
    }
}
