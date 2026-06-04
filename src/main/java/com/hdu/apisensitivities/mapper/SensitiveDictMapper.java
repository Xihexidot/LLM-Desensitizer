package com.hdu.apisensitivities.mapper;

import com.hdu.apisensitivities.dto.SensitiveDict;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SensitiveDictMapper {

    int insert(SensitiveDict dict);

    int deleteById(@Param("id") Long id);

    SensitiveDict selectById(@Param("id") Long id);

    List<SensitiveDict> selectByType(@Param("dictType") String dictType);

    List<SensitiveDict> selectEnabledByType(@Param("dictType") String dictType);

    List<SensitiveDict> selectAll();
}
