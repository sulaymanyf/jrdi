package com.acme.petclinic.api;

/**
 * Public Dubbo service interface (provider: {@code OwnerProviderImpl},
 * consumer: {@code OwnerServiceImpl}).
 */
public interface OwnerApi {
    OwnerDto findOwner(int id);
    void saveOwner(OwnerDto owner);
}
