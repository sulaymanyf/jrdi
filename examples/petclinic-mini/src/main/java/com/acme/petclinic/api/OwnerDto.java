package com.acme.petclinic.api;

/**
 * Plain DTO that travels over the Dubbo wire.
 */
public record OwnerDto(int id, String firstName, String lastName, String city) {
    public OwnerDto {
        if (firstName == null) firstName = "";
        if (lastName == null) lastName = "";
        if (city == null) city = "";
    }
}
