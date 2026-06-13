package com.acme.petclinic.mapper;

import com.acme.petclinic.api.OwnerDto;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis mapper interface. Each method is a SQL statement; jrdi will
 * parse + normalize the SQL on indexing.
 */
public interface OwnerMapper {

    @Select("SELECT id, first_name, last_name, city FROM owners WHERE id = #{id}")
    OwnerDto findById(int id);

    @Insert("INSERT INTO owners (id, first_name, last_name, city) "
            + "VALUES (#{id}, #{firstName}, #{lastName}, #{city})")
    void insert(OwnerDto owner);

    @Update("UPDATE owners SET first_name = #{firstName}, "
            + "last_name = #{lastName}, city = #{city} WHERE id = #{id}")
    void update(OwnerDto owner);

    @Delete("DELETE FROM owners WHERE id = #{id}")
    void delete(int id);
}
