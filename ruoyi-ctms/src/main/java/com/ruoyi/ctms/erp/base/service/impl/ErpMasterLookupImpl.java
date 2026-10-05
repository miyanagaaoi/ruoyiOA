package com.ruoyi.ctms.erp.base.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.ctms.domain.CtmsProduct;
import com.ruoyi.ctms.domain.CtmsUom;
import com.ruoyi.ctms.domain.CtmsWarehouse;
import com.ruoyi.ctms.erp.base.ErpMasterGuards;
import com.ruoyi.ctms.mapper.CtmsProductMapper;
import com.ruoyi.ctms.mapper.CtmsUomMapper;
import com.ruoyi.ctms.mapper.CtmsWarehouseMapper;

/**
 * <p> {@link ErpMasterGuards.MasterLookup} 的生产实现：从 B3 的三个档案 Mapper 取摘要。 </p>
 *
 * <p> <b>只读、只取摘要</b>：物料取 id/code/name/spec/uomId/enableFlag，仓库取
 * id/code/name/enableFlag，单位取 id/name/decimals/enableFlag。守卫与快照只需要这些；
 * 直接把整个实体传进公共层会让"公共层依赖档案域细节"，将来档案加字段就要同步改公共层。 </p>
 *
 * <p> <b>不做缓存</b>：停用是即时生效的强约束（tasks.md §2.3/§2.4），
 * 缓存会让"刚停用的物料仍能建单"变成一个难以复现的间歇性缺陷。 </p>
 *
 * @author 二开
 */
@Service
public class ErpMasterLookupImpl implements ErpMasterGuards.MasterLookup
{
    @Autowired(required = false)
    private CtmsProductMapper productMapper;

    @Autowired(required = false)
    private CtmsWarehouseMapper warehouseMapper;

    @Autowired(required = false)
    private CtmsUomMapper uomMapper;

    @Override
    public ErpMasterGuards.MasterRecord product(String productId)
    {
        if (productMapper == null || productId == null)
        {
            return null;
        }
        CtmsProduct product = productMapper.selectProductById(productId);
        if (product == null)
        {
            return null;
        }
        ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(
                product.getId(), product.getCode(), product.getName());
        record.setSpec(product.getSpec());
        record.setUomId(product.getUomId());
        record.setEnableFlag(product.getEnableFlag());
        return record;
    }

    @Override
    public ErpMasterGuards.MasterRecord warehouse(String warehouseId)
    {
        if (warehouseMapper == null || warehouseId == null)
        {
            return null;
        }
        CtmsWarehouse warehouse = warehouseMapper.selectWarehouseById(warehouseId);
        if (warehouse == null)
        {
            return null;
        }
        ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(
                warehouse.getId(), warehouse.getCode(), warehouse.getName());
        record.setEnableFlag(warehouse.getEnableFlag());
        return record;
    }

    @Override
    public ErpMasterGuards.MasterRecord uom(String uomId)
    {
        if (uomMapper == null || uomId == null)
        {
            return null;
        }
        CtmsUom uom = uomMapper.selectUomById(uomId);
        if (uom == null)
        {
            return null;
        }
        ErpMasterGuards.MasterRecord record = new ErpMasterGuards.MasterRecord(
                uom.getId(), uom.getCode(), uom.getName());
        record.setDecimals(uom.getDecimals());
        record.setEnableFlag(uom.getEnableFlag());
        return record;
    }
}
