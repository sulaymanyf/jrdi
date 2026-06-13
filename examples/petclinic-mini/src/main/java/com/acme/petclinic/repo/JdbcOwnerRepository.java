package com.acme.petclinic.repo;

import com.acme.petclinic.api.OwnerDto;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed implementation. The MyBatis mapper below covers the same data set,
 * which means jrdi will record two paths to the {@code owners} table.
 */
@Repository
public class JdbcOwnerRepository implements OwnerRepository {

    @Override
    public OwnerDto findById(int id) {
        // In a real app this would call JdbcTemplate.queryForObject(...)
        return new OwnerDto(id, "First", "Last", "City");
    }

    @Override
    public void save(OwnerDto owner) {
        // JdbcTemplate.update("INSERT INTO owners ...", ...)
    }
}
