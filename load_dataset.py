import json
import os
from datasets import load_dataset

def export_test_cases():
    # 1. 加载中文对话数据集
    print("正在加载数据集...")
    dataset = load_dataset("wan9yu/pii-bench-zh", data_files="data/pii_bench_zh_chat.jsonl", split="train")
    
    test_cases = []
    # 抽取前 500 条（样本量足够大且运行快）
    for i in range(min(500, len(dataset))):
        item = dataset[i]
        
        # 构造一个精简的中间格式
        case = {
            "id": item['id'],
            "content": item['text'],        # 对应 Java 的 content
            "language": "zh",
            "expected_entities": item['entities'] # 这里的实体包含 text, type, start, end
        }
        test_cases.append(case)
    
    # 2. 导出到 SpringBoot 的测试资源目录
    output_path = "./src/test/resources/my_pii_test_set.json"
    
    # 创建目录（如果不存在）
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(test_cases, f, ensure_ascii=False, indent=2)
    
    print(f"导出成功！共 {len(test_cases)} 条测试用例，保存至: {os.path.abspath(output_path)}")

if __name__ == "__main__":
    export_test_cases()