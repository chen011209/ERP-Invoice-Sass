package com.example.erpinvoicesass.mapper;

import com.example.erpinvoicesass.entity.TenantConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantConfigMapper {
    TenantConfig selectByClientId(String clientId);

    TenantConfig selectByTenantId(String tenantId);
}
