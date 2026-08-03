package com.hdu.apisensitivities.security;

import com.hdu.apisensitivities.entity.DesensitizationRequest;
import com.hdu.apisensitivities.entity.DesensitizationResponse;
import com.hdu.apisensitivities.service.DesensitizationManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多轮对话脱敏回归测试。
 * <p>
 * 保护以下真实场景缺陷修复不被回归：
 * <ul>
 * <li><b>身份证被银行卡子串吞并</b>：18 位身份证与 17 位银行卡起点重叠时，
 * 若按短 span 择优会导致身份证校验位明文残留（如 11010119900307663X → [BANK_CARD]X）；</li>
 * <li><b>普通词过度脱敏</b>："查询/余额/关联"（动词/名词以姓氏开头）被误判为人名，
 * "手机号/验证码/银行卡"（nz 专名或带"号"标签）被误判为地址；</li>
 * <li><b>实体边界</b>："张先生"称谓被当作完整人名、"张三您好"吞并问候语；</li>
 * <li><b>会话一致性</b>：同一明文在同一会话内必须映射到同一占位符。</li>
 * </ul>
 * </p>
 */
@SpringBootTest
class DialogueDesensitizationRegressionTest {

    @Autowired
    private DesensitizationManager desensitizationManager;

    private static final String DIALOGUE =
            "用户: 你好，我叫张三，我的手机号是13812345678。 \n"
                    + "助手: 您好张三，请问需要什么帮助？ \n"
                    + "用户: 张三想查询我的银行卡62284800123456789的余额。 \n"
                    + "助手: 好的张三，请确认手机号13812345678的验证码。 \n"
                    + "用户: 张先生，我的身份证号是11010119900307663X。 \n"
                    + "助手: 张三您好，系统显示身份证号11010119900307663X关联的银行卡62284800123456789... \n"
                    + "用户: 对，就是这个卡，张三确认要查询余额。 \n"
                    + "助手: 正在为张三查询银行卡62284800123456789的余额...";

    @Test
    void dialogue_noIdCardTailLeak_andPreservePlainWords_andConsistentPlaceholder() {
        DesensitizationResponse resp = desensitizationManager.process(DesensitizationRequest.builder()
                .content(DIALOGUE)
                .language("zh")
                .dataType("TEXT")
                .sessionId("dialogue-regression")
                .build());

        String out = resp.getDesensitizedContent() == null ? "" : resp.getDesensitizedContent();

        // 1) 数据泄露防护：身份证数字部分与完整明文均不得残留（含校验位 X 片段）
        assertFalse(out.contains("11010119900307663"),
                "身份证数字部分残留 -> " + out);
        assertFalse(out.contains("11010119900307663X"),
                "身份证完整明文残留 -> " + out);
        assertFalse(out.contains("62284800123456789"),
                "银行卡明文残留 -> " + out);
        assertFalse(out.contains("13812345678"),
                "手机号明文残留 -> " + out);
        assertFalse(out.contains("张三"),
                "人名明文残留 -> " + out);

        // 2) 防过度脱敏：普通业务词必须原样保留
        String[] preserved = { "查询", "余额", "关联", "张先生", "验证码", "手机号", "您好", "银行卡" };
        for (String p : preserved) {
            assertTrue(out.contains(p), "业务词被误伤：" + p + " -> " + out);
        }

        // 3) 会话一致性：所有"张三"占位符必须为同一 ID（形如 [张**_N]）
        Set<String> placeholderIds = new HashSet<>();
        Matcher m = PLACEHOLDER_PATTERN.matcher(out);
        while (m.find()) {
            placeholderIds.add(m.group(1));
        }
        assertTrue(placeholderIds.size() == 1,
                "同一明文出现多个占位符 ID（会话一致性被破坏）-> " + placeholderIds + " | " + out);
    }

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[张\\*\\*_(\\d+)]");
}
