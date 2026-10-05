/**
 * <p> 合同台账域的 <b>MyBatis Mapper 接口</b>。 </p>
 *
 * <p> 本包被全局 {@code @MapperScan("com.ruoyi.**.mapper")} 扫描
 * （{@code ruoyi-framework/.../config/ApplicationConfig.java:19}），
 * 因此接口放在这里即自动注册，不需要额外配置。 </p>
 *
 * <p> 对应的 XML 放 {@code resources/mapper/ctms/}，<b>文件名必须以 {@code Mapper.xml} 结尾</b>
 * —— 扫描规则是 {@code mapperLocations: classpath*:mapper/**}{@code /*Mapper.xml}
 * （见 {@code ruoyi-admin/src/main/resources/env/dev/application.yml:142}），
 * 不按这个规则命名的 XML 会<b>静默加载不到</b>（表现为 "Invalid bound statement"，很难查）。 </p>
 *
 * <p> ⚠ 数据范围（D-4）的判定片段要写在本层的 XML 里，且**列表/详情/编辑/删除/恢复/导出共用同一段**：
 * 口径分散在多处 SQL 是本批次最高风险项（R-5：越权或"该看的看不到"）。
 * 判定语义（{@code SELF}/{@code DEPT} 含下级/{@code ALL}、多角色<b>并集</b>）见
 * {@code openspec/changes/oa-contract-ledger/notes/data-scope-matrix.md}。 </p>
 *
 * @author 二开
 */
package com.ruoyi.ctms.mapper;
