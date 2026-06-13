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
 */package com.acme.petclinic.mapper;

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
