package com.acme.petclinic.config;

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
