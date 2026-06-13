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
