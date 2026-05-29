package org.gms.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gms.config.GameConfig;
import org.gms.property.ServiceProperty;
import org.gms.dao.entity.GameConfigDO;
import org.gms.dao.entity.LangResourcesDO;
import org.gms.dao.entity.table.LangResourcesDOTableDef;
import org.gms.dao.mapper.GameConfigMapper;
import org.gms.dao.mapper.LangResourcesMapper;
import org.gms.exception.BizException;
import org.gms.model.dto.ConfigI18nDTO;
import org.gms.model.dto.ConfigTypeDTO;
import org.gms.model.dto.GameConfigReqDTO;
import org.gms.net.server.Server;
import org.gms.util.I18nUtil;
import org.gms.util.RequireUtil;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.mybatisflex.core.query.QueryMethods.distinct;
import static org.gms.dao.entity.table.GameConfigDOTableDef.GAME_CONFIG_DO;
import static org.gms.dao.entity.table.LangResourcesDOTableDef.LANG_RESOURCES_DO;

@Service
@AllArgsConstructor
@Slf4j
public class ConfigService {
    private static final String GAME_CONFIG_I18N_BASE = "game_config";
    private static final String GAME_CONFIG_JSON_I18N_BASE = "game_config_json_i18n";
    private static final List<String> CONFIG_I18N_BASES = List.of(GAME_CONFIG_I18N_BASE, GAME_CONFIG_JSON_I18N_BASE);

    private final GameConfigMapper gameConfigMapper;
    private final LangResourcesMapper langResourcesMapper;
    private final ServiceProperty serviceProperty;
    private final LangResourceService langResourceService;
    private final ObjectMapper objectMapper;

    public List<GameConfigDO> loadGameConfigs() {
        return gameConfigMapper.selectAll();
    }

    public ConfigTypeDTO getConfigTypeList() {
        List<GameConfigDO> typeDOList = gameConfigMapper.selectListByQuery(QueryWrapper.create().select(distinct(GAME_CONFIG_DO.CONFIG_TYPE)));
        List<GameConfigDO> subTypeDOList = gameConfigMapper.selectListByQuery(QueryWrapper.create().select(distinct(GAME_CONFIG_DO.CONFIG_SUB_TYPE)));
        return ConfigTypeDTO.builder()
                .types(typeDOList.stream().map(GameConfigDO::getConfigType).toList())
                .subTypes(subTypeDOList.stream().map(GameConfigDO::getConfigSubType).toList())
                .build();
    }

    public Page<GameConfigDO> getConfigList(GameConfigReqDTO condition) {
        LangResourcesDOTableDef jsonI18n = LANG_RESOURCES_DO.as("json_i18n");
        // join i18n表
        QueryWrapper queryWrapper = QueryWrapper.create()
                .select(GAME_CONFIG_DO.ID, GAME_CONFIG_DO.CONFIG_CODE, GAME_CONFIG_DO.CONFIG_CLAZZ,
                        GAME_CONFIG_DO.CONFIG_TYPE, GAME_CONFIG_DO.CONFIG_SUB_TYPE, GAME_CONFIG_DO.CONFIG_VALUE,
                        LANG_RESOURCES_DO.LANG_VALUE.as("config_desc"))
                .from(GAME_CONFIG_DO)
                .leftJoin(LANG_RESOURCES_DO).on(LANG_RESOURCES_DO.LANG_CODE.eq(GAME_CONFIG_DO.CONFIG_DESC)
                        .and(LANG_RESOURCES_DO.LANG_TYPE.eq(serviceProperty.getLanguage()))
                        .and(LANG_RESOURCES_DO.LANG_BASE.eq(GAME_CONFIG_I18N_BASE)))
                .leftJoin(jsonI18n).on(jsonI18n.LANG_CODE.eq(GAME_CONFIG_DO.CONFIG_CODE)
                        .and(jsonI18n.LANG_TYPE.eq(serviceProperty.getLanguage()))
                        .and(jsonI18n.LANG_BASE.eq(GAME_CONFIG_JSON_I18N_BASE)));
        if (!RequireUtil.isEmpty(condition.getType()))
            queryWrapper.and(GAME_CONFIG_DO.CONFIG_TYPE.eq(condition.getType()));
        if (!RequireUtil.isEmpty(condition.getSubType()))
            queryWrapper.and(GAME_CONFIG_DO.CONFIG_SUB_TYPE.eq(condition.getSubType()));
        if (!RequireUtil.isEmpty(condition.getFilter())) {
            queryWrapper.and(GAME_CONFIG_DO.CONFIG_CODE.like(condition.getFilter())
                    .or(LANG_RESOURCES_DO.LANG_VALUE.like(condition.getFilter()))
                    .or(jsonI18n.LANG_VALUE.like(condition.getFilter())));
        }

        Page<GameConfigDO> page = gameConfigMapper.paginate(condition.getPageNo(), condition.getPageSize(), queryWrapper);
        fillJsonConfigDescriptions(page.getRecords());
        return page;
    }

    private void fillJsonConfigDescriptions(List<GameConfigDO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (GameConfigDO record : records) {
            if (!"json".equalsIgnoreCase(record.getConfigClazz())) {
                continue;
            }
            listConfigI18n(record.getConfigCode(), List.of(GAME_CONFIG_JSON_I18N_BASE))
                    .stream()
                    .filter(langResource -> serviceProperty.getLanguage().equals(langResource.getLangType()))
                    .map(LangResourcesDO::getLangValue)
                    .filter(langValue -> langValue != null && !langValue.isBlank())
                    .findFirst()
                    .ifPresent(record::setConfigDesc);
        }
    }

    public ConfigI18nDTO getConfigI18n(String configCode) {
        RequireUtil.requireNotEmpty(configCode, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configCode"));
        String langType = serviceProperty.getLanguage();
        ConfigI18nDTO result = ConfigI18nDTO.builder()
                .configCode(configCode)
                .langType(langType)
                .configDesc("")
                .jsonDesc("")
                .build();
        listConfigI18n(configCode, CONFIG_I18N_BASES).forEach(langResource -> {
            if (!langType.equals(langResource.getLangType())) {
                return;
            }
            if (GAME_CONFIG_JSON_I18N_BASE.equals(langResource.getLangBase())) {
                result.setJsonDesc(langResource.getLangValue());
            }
            if (GAME_CONFIG_I18N_BASE.equals(langResource.getLangBase())) {
                result.setConfigDesc(langResource.getLangValue());
            }
        });
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateConfigI18n(ConfigI18nDTO condition) {
        RequireUtil.requireNotEmpty(condition.getConfigCode(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configCode"));
        String langType = serviceProperty.getLanguage();
        upsertConfigI18n(GAME_CONFIG_I18N_BASE, condition.getConfigCode(), langType, condition.getConfigDesc());
        upsertConfigI18n(GAME_CONFIG_JSON_I18N_BASE, condition.getConfigCode(), langType, condition.getJsonDesc());
    }

    private void upsertConfigI18n(String langBase, String langCode, String langType, String langValue) {
        if (langValue == null) {
            return;
        }
        LangResourcesDO langResourcesDO = LangResourcesDO.builder()
                .langBase(langBase)
                .langCode(langCode)
                .langType(langType)
                .langValue(langValue)
                .build();
        List<LangResourcesDO> exists = listConfigI18n(langCode, List.of(langBase))
                .stream()
                .filter(langResource -> langType.equals(langResource.getLangType()))
                .toList();
        if (exists.size() == 1) {
            langResourcesDO.setId(exists.getFirst().getId());
            langResourcesMapper.update(langResourcesDO);
            return;
        }
        if (exists.size() > 1) {
            langResourcesMapper.deleteBatchByIds(exists.stream().map(LangResourcesDO::getId).collect(Collectors.toList()));
        }
        langResourcesMapper.insert(langResourcesDO);
    }

    private List<LangResourcesDO> listConfigI18n(String langCode, Collection<String> langBases) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .where(LANG_RESOURCES_DO.LANG_CODE.eq(langCode));
        if (langBases != null && !langBases.isEmpty()) {
            queryWrapper.and(LANG_RESOURCES_DO.LANG_BASE.in(langBases));
        }
        return langResourcesMapper.selectListByQuery(queryWrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void addConfig(GameConfigDO condition) {
        RequireUtil.requireNotEmpty(condition.getConfigType(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configType"));
        RequireUtil.requireNotEmpty(condition.getConfigSubType(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configSubType"));
        RequireUtil.requireNotEmpty(condition.getConfigCode(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configCode"));
        RequireUtil.requireNotEmpty(condition.getConfigValue(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configValue"));
        if ("json".equalsIgnoreCase(condition.getConfigClazz())) {
            validateJsonConfigValue(condition.getConfigValue());
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .where(GAME_CONFIG_DO.CONFIG_TYPE.eq(condition.getConfigType()))
                .and(GAME_CONFIG_DO.CONFIG_CODE.eq(condition.getConfigCode()));
        if ("world".equals(condition.getConfigType())) {
            queryWrapper.and(GAME_CONFIG_DO.CONFIG_SUB_TYPE.eq(condition.getConfigSubType()));
        }
        List<GameConfigDO> gameConfigDOList = gameConfigMapper.selectListByQuery(queryWrapper);
        RequireUtil.requireTrue(gameConfigDOList.isEmpty(), I18nUtil.getExceptionMessage("ConfigService.addConfig.exception1"));
        if (condition.getConfigDesc() != null) {
            langResourceService.insertOrUpdateI18n(LangResourcesDO.builder()
                    .langBase("game_config")
                    .langCode(condition.getConfigCode())
                    .langType(serviceProperty.getLanguage())
                    .langValue(condition.getConfigDesc())
                    .build());
        }
        condition.setId(null);
        condition.setConfigDesc(condition.getConfigCode());
        condition.setUpdateTime(new Date(System.currentTimeMillis()));
        gameConfigMapper.insertSelective(condition);
        GameConfig.add(condition);
    }

    @Transactional(rollbackFor = Exception.class)
    public GameConfigDO upsertServerConfig(String subType, String code, String clazz, String value, String desc) {
        RequireUtil.requireNotEmpty(subType, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configSubType"));
        RequireUtil.requireNotEmpty(code, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configCode"));
        RequireUtil.requireNotEmpty(value, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configValue"));
        QueryWrapper queryWrapper = QueryWrapper.create()
                .where(GAME_CONFIG_DO.CONFIG_TYPE.eq("server"))
                .and(GAME_CONFIG_DO.CONFIG_SUB_TYPE.eq(subType))
                .and(GAME_CONFIG_DO.CONFIG_CODE.eq(code));
        GameConfigDO gameConfigDO = gameConfigMapper.selectOneByQuery(queryWrapper);
        Date updateTime = new Date(System.currentTimeMillis());
        if (gameConfigDO == null) {
            gameConfigDO = GameConfigDO.builder()
                    .configType("server")
                    .configSubType(subType)
                    .configClazz(clazz == null || clazz.isBlank() ? String.class.getName() : clazz)
                    .configCode(code)
                    .configValue(value)
                    .configDesc(code)
                    .updateTime(updateTime)
                    .build();
            gameConfigMapper.insertSelective(gameConfigDO);
            langResourceService.insertOrUpdateI18n(LangResourcesDO.builder()
                    .langBase("game_config")
                    .langCode(code)
                    .langType(serviceProperty.getLanguage())
                    .langValue(desc == null || desc.isBlank() ? code : desc)
                    .build());
            GameConfig.add(gameConfigDO);
            return gameConfigDO;
        }

        GameConfigDO update = GameConfigDO.builder()
                .id(gameConfigDO.getId())
                .configValue(value)
                .configClazz(clazz == null || clazz.isBlank() ? gameConfigDO.getConfigClazz() : clazz)
                .updateTime(updateTime)
                .build();
        gameConfigMapper.update(update);
        if (desc != null && !desc.isBlank()) {
            langResourceService.insertOrUpdateI18n(LangResourcesDO.builder()
                    .langBase("game_config")
                    .langCode(code)
                    .langType(serviceProperty.getLanguage())
                    .langValue(desc)
                    .build());
        }
        gameConfigDO.setConfigValue(value);
        gameConfigDO.setConfigClazz(update.getConfigClazz());
        gameConfigDO.setUpdateTime(updateTime);
        GameConfig.update(gameConfigDO);
        return gameConfigDO;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(GameConfigDO condition) {
        RequireUtil.requireNotNull(condition.getId(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_NULL", "id"));
        RequireUtil.requireNotEmpty(condition.getConfigValue(), I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configValue"));
        GameConfigDO gameConfigDO = gameConfigMapper.selectOneById(condition.getId());
        if ("json".equalsIgnoreCase(gameConfigDO.getConfigClazz())) {
            validateJsonConfigValue(condition.getConfigValue());
        }
        if (condition.getConfigDesc() != null) {
            langResourceService.insertOrUpdateI18n(LangResourcesDO.builder()
                    .langBase("game_config")
                    .langCode(gameConfigDO.getConfigCode())
                    .langType(serviceProperty.getLanguage())
                    .langValue(condition.getConfigDesc())
                    .build());
        }
        gameConfigMapper.update(GameConfigDO.builder()
                .id(condition.getId())
                .configValue(condition.getConfigValue())
                .updateTime(new Date(System.currentTimeMillis()))
                .build());
        gameConfigDO.setConfigValue(condition.getConfigValue());
        GameConfig.update(gameConfigDO);
    }

    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateConfig(List<GameConfigDO> configList) {
        RequireUtil.requireNotEmpty(configList, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "configList"));
        for (GameConfigDO config : configList) {
            updateConfig(config);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id) {
        RequireUtil.requireNotNull(id, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_NULL", "id"));
        GameConfigDO gameConfigDO = gameConfigMapper.selectOneById(id);
        langResourceService.deleteI18n(LangResourcesDO.builder()
                .langBase("game_config")
                .langCode(gameConfigDO.getConfigCode())
                // 所有i18n都要删掉
//                .langType(serviceProperty.getLanguage())
                .build());
        gameConfigMapper.deleteById(id);
        GameConfig.remove(gameConfigDO);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteConfigList(List<Long> ids) {
        RequireUtil.requireNotEmpty(ids, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "ids"));
        ids.forEach(this::deleteConfig);
    }

    public int importYml(MultipartFile file) {
        String filename = file.getOriginalFilename();
        RequireUtil.requireNotEmpty(filename, I18nUtil.getExceptionMessage("PARAMETER_SHOULD_NOT_EMPTY", "file"));
        RequireUtil.requireTrue(filename.endsWith(".yml") || filename.endsWith(".yaml"), I18nUtil.getExceptionMessage("UNSUPPORTED_TYPE") + ": " + filename);
        try {
            Yaml yaml = new Yaml();
            LinkedHashMap<String, Object> property = yaml.load(file.getInputStream());
            JSONObject gmsProperty = JSONObject.parse(JSONObject.toJSONString(property.get("gms")));
            JSONArray worlds = gmsProperty.getJSONObject("world").getJSONArray("worlds");
            JSONObject server = gmsProperty.getJSONObject("server");

            StringBuilder updateSql = new StringBuilder();
            for (int i = 0; i < worlds.size(); i++) {
                JSONObject world = worlds.getJSONObject(i);
                if (world.getFloat("exp_rate") == null) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : world.entrySet()) {
                    String configCode = entry.getKey().toLowerCase();
                    configCode = replaceWithEquals(configCode, new String[]{"why_am_i_recommended", "recommend_message"},
                            new String[]{"channels", "channel_size"});
                    updateSql.append("update game_config set config_value = '").append(parseObject(entry.getValue()))
                            .append("' where config_type = 'world' and config_sub_type = '").append(i)
                            .append("' and config_code = '").append(configCode).append("';\n");
                }
            }
            for (Map.Entry<String, Object> entry : server.entrySet()) {
                String configCode = entry.getKey().toLowerCase();
                configCode = replaceWithEquals(configCode, new String[]{"wldlist_size", "max_world_size"},
                        new String[]{"channel_size", "max_channel_size"}, new String[]{"channel_load", "channel_capacity"},
                        new String[]{"host", "wan_host"}, new String[]{"lanhost", "lan_host"},
                        new String[]{"use_debug_show_rcvd_mvlife", "use_debug_show_life_move"});
                configCode = replaceWithContains(configCode, new String[]{"use_maxrange", "use_max_range"},
                        new String[]{"charslot", "chr_slot"}, new String[]{"multiclient", "multi_client"},
                        new String[]{"keyset", "key_set"}, new String[]{"eqpexp", "eqp_exp"},
                        new String[]{"autoassign", "auto_assign"}, new String[]{"autoban", "auto_ban"},
                        new String[]{"openshop", "open_shop"}, new String[]{"shopitemsold", "shop_item_sold"},
                        new String[]{"cashshop", "cash_shop"}, new String[]{"atkup", "atk_up"},
                        new String[]{"unitprice", "unit_price"}, new String[]{"buffstat", "buff_stat"},
                        new String[]{"autoaggro", "auto_aggro"}, new String[]{"chscroll", "chaos_scroll"},
                        new String[]{"skillset", "skill_set"}, new String[]{"equipmnt", "equipment"},
                        new String[]{"lvlup", "level_up"}, new String[]{"levelup", "level_up"},
                        new String[]{"extraheal", "extra_heal"}, new String[]{"autopot", "auto_pot"},
                        new String[]{"autohp", "auto_hp"}, new String[]{"automp", "auto_mp"});

                Object configValue = parseObject(entry.getValue());
                if ("npcs_scriptable".equalsIgnoreCase(entry.getKey())) {
                    configValue = JSONObject.toJSONString(entry.getValue());
                }
                updateSql.append("update game_config set config_value = '").append(configValue)
                        .append("' where config_type = 'server' and config_code = '").append(configCode).append("';\n");
            }
            String[] updateArr = updateSql.toString().split("\n");
            for (String str : updateArr) {
                Db.updateBySql(str);
            }

        } catch (Exception e) {
            String msg = I18nUtil.getExceptionMessage("FILE_PARSE_ERROR");
            log.error(msg, e);
            throw new BizException(msg);
        }
        // 异步重启，这里千万不要用ThreadManager，因为停止服务会注销所有线程
        Thread.startVirtualThread(Server.getInstance().shutdown(true, false));
        // 返回成功的数量
        return 1;
    }

    private Object parseObject(Object obj) {
        String typeName = obj.getClass().getTypeName();
        // 为避免科学计数，进行格式化输出，以double为标准最多精确到16位
        if ("java.lang.Float".equals(typeName) || "java.lang.Double".equals(typeName)) {
            return new DecimalFormat("#.################").format(obj);
        }
        return obj;
    }

    private String replaceWithEquals(String src, String[]... fts) {
        for (String[] ft : fts) {
            if (ft[0].equals(src)) {
                src = ft[1];
                break;
            }
        }
        return src;
    }

    private String replaceWithContains(String src, String[]... fts) {
        for (String[] ft : fts) {
            if (src.contains(ft[0])) {
                src = src.replace(ft[0], ft[1]);
            }
        }
        return src;
    }

    /**
     * 校验 JSON 参数值是否为标准 JSON。
     *
     * GameConfig JSON 参数统一使用 configClazz=json 标记。
     * 保存前必须校验 configValue，防止非法 JSON 写入数据库后影响前端编辑和业务解析。
     *
     * @param configValue 待保存的 JSON 字符串
     */
    private void validateJsonConfigValue(String configValue) {
        try {
            objectMapper.readTree(configValue);
        } catch (JsonProcessingException e) {
            throw new BizException("JSON 格式错误，请检查后再保存");
        }
    }

    /**
     * 将对象写出为标准 JSON 字符串。
     *
     * 使用项目统一 Jackson ObjectMapper，禁止写出 JSONC、尾逗号或非标准 JSON。
     *
     * @param value 待写出的对象
     * @return 标准 JSON 字符串
     */
    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException("JSON 写出失败");
        }
    }

    /**
     * 按 Class 读取 JSON 参数。
     *
     * @param configCode 配置编码
     * @param clazz      目标类型
     * @param defaultValue 默认值
     * @param <T>        目标类型
     * @return 解析后的对象；缺失或解析失败时返回默认值
     */
    public <T> T readJson(String configCode, Class<T> clazz, T defaultValue) {
        String json = GameConfig.getServerJsonString(configCode);
        if (json.isEmpty()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.warn("JSON 参数 {} 解析失败，使用默认值", configCode, e);
            return defaultValue;
        }
    }

    /**
     * 按 Jackson TypeReference 读取泛型 JSON 参数。
     *
     * @param configCode    配置编码
     * @param typeReference Jackson TypeReference
     * @param defaultValue  默认值
     * @param <T>           目标泛型类型
     * @return 解析后的对象；缺失或解析失败时返回默认值
     */
    public <T> T readJson(String configCode,
                          com.fasterxml.jackson.core.type.TypeReference<T> typeReference,
                          T defaultValue) {
        String json = GameConfig.getServerJsonString(configCode);
        if (json.isEmpty()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            log.warn("JSON 参数 {} 解析失败，使用默认值", configCode, e);
            return defaultValue;
        }
    }

    public ResponseEntity<Resource> exportYml() {
        List<GameConfigDO> gameConfigDOS = loadGameConfigs();
        // 转成yml格式
        Map<String, List<GameConfigDO>> worldCollect = gameConfigDOS.stream().filter(config -> "world".equals(config.getConfigType()))
                .collect(Collectors.groupingBy(GameConfigDO::getConfigSubType));
        List<Map<String, Object>> worldList = new ArrayList<>();
        for (Map.Entry<String, List<GameConfigDO>> entry : worldCollect.entrySet()) {
            worldList.add(entry.getValue().stream().collect(toMap()));
        }
        Map<String, Object> worlds = new HashMap<>();
        worlds.put("worlds", worldList);

        Map<String, Object> serverCollect = gameConfigDOS.stream().filter(config -> "server".equals(config.getConfigType()))
                .collect(toMap());

        Map<String, Object> gms = new HashMap<>();
        gms.put("world", worlds);
        gms.put("server", serverCollect);

        Map<String, Object> ymlCollect = new HashMap<>();
        ymlCollect.put("gms", gms);

        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
            Yaml yaml = new Yaml(options);
            StringWriter writer = new StringWriter();
            yaml.dump(ymlCollect, writer);
            byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);
            Resource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return "export.yml";
                }
            };
            HttpHeaders headers = new HttpHeaders();
            // 需要将CONTENT_DISPOSITION暴露给前端，否则前端识别不到头信息的CONTENT_DISPOSITION
            headers.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"");
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        } catch (Exception e) {
            String msg = I18nUtil.getExceptionMessage("FILE_CREATE_ERROR");
            log.error(msg, e);
            throw new BizException(msg);
        }

    }

    public Collector<GameConfigDO, ?, Map<String, Object>> toMap() {
        return Collectors.toMap(GameConfigDO::getConfigCode, config -> {
            if ("java.util.Map".equals(config.getConfigClazz())) {
                return JSONObject.parseObject(config.getConfigValue(), new TypeReference<Map<Integer, Object>>() {
                });
            } else if ("java.lang.Float".equals(config.getConfigClazz()) || "java.lang.Double".equals(config.getConfigClazz())) {
                // 为避免科学计数，用BigDecimal
                return new BigDecimal(config.getConfigValue());
            } else if ("json".equalsIgnoreCase(config.getConfigClazz())) {
                // JSON 类型：用 Jackson 解析为结构化对象，使 YAML 导出格式正确
                try {
                    return objectMapper.readValue(config.getConfigValue(), Object.class);
                } catch (Exception e) {
                    return config.getConfigValue();
                }
            } else {
                try {
                    return JSONObject.parseObject(config.getConfigValue(), Class.forName(config.getConfigClazz()));
                } catch (Exception e) {
                    return config.getConfigValue();
                }
            }
        }, (v1, v2) -> v1);
    }
}
