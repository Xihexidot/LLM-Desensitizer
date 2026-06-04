package com.hdu.apisensitivities.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 敏感词典实体：统一管理白名单/黑名单词条。
 * 词典类型 (dict_type)：
 *   SURNAME_WHITELIST  - 姓氏白名单（中文单字姓）
 *   PERSON_BLACKLIST   - 人名黑名单（误报为非人名的词）
 *   ADDRESS_BLACKLIST  - 地址黑名单（误报为地址的词）
 *   ADDRESS_SUFFIXES   - 地址后缀白名单（路/街/巷/号 等）
 */
@Data
public class SensitiveDict {
    private Long id;
    private String dictType;
    private String term;
    private Boolean isEnabled;
    private LocalDateTime createdAt;
}
