import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class IdCardRegexTest {
    public static void main(String[] args) {
        String text = "420104 20051104 4650";
        Pattern p = Pattern.compile(
                "(?<!\\d)[1-9]\\d{5}\\s*(?:18|19|20)\\d{2}\\s*(?:0[1-9]|1[0-2])\\s*(?:0[1-9]|[12]\\d|3[01])\\s*\\d{3}[0-9Xx](?!\\d)");
        Matcher m = p.matcher(text);
        boolean found = m.find();
        System.out.println("匹配结果: " + found);
        if (found) {
            System.out.println("匹配到的内容: " + m.group());
            System.out.println("起始位置: " + m.start());
            System.out.println("结束位置: " + m.end());
        } else {
            System.out.println("未匹配到任何身份证号");
        }
    }
}