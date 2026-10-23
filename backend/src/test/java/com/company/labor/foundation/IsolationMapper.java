package com.company.labor.foundation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface IsolationMapper extends BaseMapper<IsolationRecord> {
    @Select("SELECT id, tenant_id, value FROM isolation_fixture ORDER BY id")
    List<Map<String, Object>> all();

    @Insert("INSERT INTO isolation_fixture(id, value) VALUES (#{id}, 'fixture')")
    int insertFixture(long id);

    @Insert("INSERT INTO isolation_fixture(id, tenant_id, value) VALUES (#{id}, #{tenant}, 'fixture')")
    int explicitInsert(@Param("id") long id, @Param("tenant") long tenant);

    @Update("UPDATE isolation_fixture SET value = 'changed' WHERE id = #{id}")
    int updateFixture(long id);

    @Update("UPDATE isolation_fixture SET tenant_id = #{tenant} WHERE id = #{id}")
    int move(@Param("id") long id, @Param("tenant") long tenant);

    @Delete("DELETE FROM isolation_fixture WHERE id = #{id}")
    int deleteFixture(long id);

    @Select("SELECT COUNT(*) FROM unregistered_fixture")
    long unregistered();
}
