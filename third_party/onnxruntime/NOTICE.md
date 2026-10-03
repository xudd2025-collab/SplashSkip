# Offline text recognition

- ONNX Runtime Android **1.20.0** (`com.microsoft.onnxruntime:onnxruntime-android`), Microsoft, MIT license. Java API and the four Android native ABIs are included. See `LICENSE.txt` and `ThirdPartyNotices.txt`.
- **PP-OCRv4** Chinese mobile detection and recognition weights, PaddlePaddle authors, Apache-2.0. See `PaddleOCR-LICENSE.txt`.
- The ONNX conversions are distributed in **RapidOCR ONNXRuntime 1.4.4**, RapidAI contributors, Apache-2.0. See `RapidOCR-LICENSE.txt`.

Sources:

- https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.20.0/onnxruntime-android-1.20.0.aar
- https://pypi.org/project/rapidocr-onnxruntime/1.4.4/
- https://github.com/PaddlePaddle/PaddleOCR
- https://github.com/RapidAI/RapidOCR

`ui_text_det.onnx` and `ui_text_rec.onnx` are copied unchanged from that Python distribution. `ui_text_keys.txt` is the recognition model's `character` metadata exported to UTF-8, with the blank and space classes added by the decoder. No user screenshot or advertising creative was used to change these weights. The new screenshots are evaluation inputs only and are not included in the APK or source repository.

The inference engine performs detection and CTC decoding locally. It does not use a hosted OCR service, fetch models on first use, or transmit recognized text. The text engine uses CPU inference with two threads; attaching an NPU is not claimed. Older TFLite button models retain their existing NNAPI/CPU fallback.

The asset hashes and the downloaded artifact hash are recorded in `provenance.json`.
