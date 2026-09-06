package com.apparat.exception;

public class ResourceNotFoundException extends ApparatException {
    public ResourceNotFoundException(String entityType, Object id) {
        super(entityType + " " + id + " was not found.", 404);
    }
}
