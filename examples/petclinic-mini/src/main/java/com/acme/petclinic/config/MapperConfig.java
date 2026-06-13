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
 */package com.acme.petclinic.config;

import com.acme.petclinic.mapper.OwnerMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring @Configuration that exposes the MyBatis mapper as a bean under
 * the name "ownerMapper". The @Bean declaration is one of the wiring
 * facts jrdi records.
 */
@Configuration
public class MapperConfig {

    @Bean
    public OwnerMapper ownerMapper() {
        // In a real app this would return a SqlSessionFactory-built proxy.
        return null;
    }
}
