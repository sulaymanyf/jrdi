package com.acme.petclinic.service;

import com.acme.petclinic.api.OwnerDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Spring service that the controller delegates to. Wires the Dubbo {@code OwnerApi}
 * consumer (via Spring's @Autowired) and the JdbcOwnerRepository.
 */
@Service
public class OwnerService {

    @Autowired
    private com.acme.petclinic.api.OwnerApi ownerApi;

    @Autowired
    private com.acme.petclinic.repo.OwnerRepository ownerRepository;

    public OwnerDto getOwner(int id) {
        // Delegation: service uses both the Dubbo remote API and the local repo.
        OwnerDto remote = ownerApi.findOwner(id);
        if (remote == null) {
            return ownerRepository.findById(id);
        }
        return remote;
    }

    public void saveOwner(OwnerDto owner) {
        ownerRepository.save(owner);
    }
}
