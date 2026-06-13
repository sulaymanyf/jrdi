/*
 * Copyright 2026 sulaymanyf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */package com.acme.petclinic.service;

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
