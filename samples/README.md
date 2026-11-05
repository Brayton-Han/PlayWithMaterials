# 演示上传样例（T-021）

由 [scripts/gen-seed-assets.py](../scripts/gen-seed-assets.py) 生成，CC0 1.0，来源说明见根目录 README「素材来源」。

| 文件 | 真实类型 / 大小 | 演示目的（对照 V2 种子模板） |
| --- | --- | --- |
| `sample-1.jpg` ~ `sample-3.jpg` | JPEG，约 20 KB | 合法图片，可用于 `demo-postcard`（2 张）、`creative-poster`（1 张）、`festival-collage`（3 张） |
| `sample-1.png` ~ `sample-3.png` | PNG，约 15 KB | 合法 PNG，可用于全部模板，包括只接受 PNG、上限 1 MB 的 `portrait-id-photo` |
| `sample.webp` | WebP，约 4 KB | `creative-poster` / `festival-collage` 可接受；`demo-postcard`、`portrait-id-photo` 应拒绝（`FILE_TYPE_NOT_ACCEPTED`） |
| `over-1mb.png` | PNG，约 1.4 MB | 只超过 `portrait-id-photo` 的 1 MB 上限（`FILE_TOO_LARGE`），其他模板可接受 |
| `over-5mb.png` | PNG，约 5.8 MB | 超过所有模板的上限（`FILE_TOO_LARGE`） |
| `fake-image.jpg` | 纯文本，扩展名伪装为 .jpg | 后端按文件头魔数检测应拒绝（`FILE_TYPE_NOT_ACCEPTED`）；前端只看 MIME 时可能放过，用来验证后端兜底 |
