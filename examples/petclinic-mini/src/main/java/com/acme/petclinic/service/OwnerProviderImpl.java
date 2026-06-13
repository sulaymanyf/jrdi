package com.acme.petclinic.service;

import com.acme.petclinic.api.OwnerApi;
import com.acme.petclinic.api.OwnerDto;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * Provider side of the OwnerApi Dubbo service. Spring picks it up via
 * {@code @Service} so a local @Autowired can also reference it, and Dubbo
 * exports it remotely via {@code @DubboService}.
 */
@org.springframework.stereotype.Service
@DubboService
public class OwnerProviderImpl implements OwnerApi {

    @Override
    public OwnerDto findOwner(int id) {
        return new OwnerDto(id, "Remote", "Owner", "DubboCity");
    }

    @Override
    public void saveOwner(OwnerDto owner) {
        // persisted remotely by the real provider
    }
}
