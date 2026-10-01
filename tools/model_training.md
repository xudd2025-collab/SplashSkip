# 模型训练

`train_skip_model.py` 公开当前分类器的 NumPy 训练与 TFLite 导出实现：32 × 20 灰度输入，标准化后展平，16 个 ReLU 隐藏单元和一个 sigmoid 输出。随机种子固定，训练 1200 步。它只判断候选裁剪是否包含跳过文字，不是通用 OCR。

原始采集数据含个人页面，因此不公开；缺少相同数据不能复现当前模型的完全相同权重。公开仓库包含现有 `skip_text.tflite`，构建 APK 无需再次训练。

## 准备数据

使用自行采集、获准使用且已脱敏的截图。创建 JSON 数组，每条为：

```json
[
  {"file":"positive.png", "box":[950,90,1060,150], "label":1, "split":"train"},
  {"file":"normal.png", "box":[950,90,1060,150], "label":0, "split":"train"},
  {"file":"unseen.png", "box":[950,90,1060,150], "label":1, "split":"validation"}
]
```

图片路径相对 JSON 文件。`box` 是原图像素的左、上、右、下边界；必须按实际样本标注，示例坐标不能用于盲点。正负样本都必须存在。验证集按原始广告/采集来源隔离，不能只把同一截图的增强图放入验证集。

## 运行

```powershell
python -m pip install -r tools/model-requirements.txt
python tools/train_skip_model.py --dataset C:\private\dataset.json --output C:\private\candidate.tflite
```

安装依赖需要网络；训练不联网、不自动搜索 Downloads，不上传数据。先在独立输出路径评估，再决定是否替换 `app/src/main/assets/skip_text.tflite`。训练集分数不能证明真机识别准确，阈值和候选区域仍须与 Java 识别逻辑一起验证。

新布局需要同时补充正负样本、候选区域与点击前检查。发布前检查正常页面误识别和真实点击前后结果。
