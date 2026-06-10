public class LuhnValidator {

    public static void main(String[] args) {
        // 你想要测试的银行卡号
        String cardNumber = "6225684192832768"; // 替换成你要验证的卡号

        boolean valid = validateBankCard(cardNumber);
        System.out.println("银行卡号: " + cardNumber);
        System.out.println("Luhn校验结果: " + (valid ? "有效" : "无效"));
    }

    /**
     * 使用 Luhn 算法校验银行卡号有效性。
     * （直接从 RegexDetectionService 复制而来）
     */
    private static boolean validateBankCard(String bankCard) {
        if (bankCard == null) {
            return false;
        }
        String normalized = bankCard.replaceAll("[\\s-]", "");
        if (normalized.length() < 13 || normalized.length() > 19 || !normalized.matches("\\d+")) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = normalized.length() - 1; i >= 0; i--) {
            int n = Integer.parseInt(normalized.substring(i, i + 1));
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }
}