package com.acme.petclinic.repo;

import com.acme.petclinic.api.OwnerDto;

/**
 * Repository contract. The JDBC implementation is below; the MyBatis mapper
 * covers the same data with annotated SQL.
 */
public interface OwnerRepository {
    OwnerDto findById(int id);
    void save(OwnerDto owner);
}
