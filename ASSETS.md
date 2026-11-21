# 素材来源与署名

## 胶卷、照片和重绘

选卷资料按品牌、型号、版次和片幅核对。照片以 Wikimedia Commons 的有许可文件为主，型号与片幅同时参考厂商资料。应用里的「实拍与来源」展示对应的照片、署名和出处。选卷缩略图采用 ImageGen 统一角度、光线和背景。实拍图通常调整尺寸、裁取相应胶卷或转换为 WebP。 [逐卷来源与许可](app/src/main/assets/films/catalog.tsv) · [首发四卷和图标](app/src/main/assets/licenses/ASSETS.md) · [Foma 官方目录](https://www.foma.cz/en/catalogue-bw-film-324)

同一胶卷的不同包装和不同颜色预设会分别对照。富士 Provia 100F 可用 `RDP III` 核对 135 版本，Foma 100 Classic 使用厂商资料。Kodak Pro Image 100 的原照来自 Jakob Owens，保留 Commons 的授权记录。 [Kodak Pro Image 原照与许可](https://commons.wikimedia.org/wiki/File:Pro_Image_%28Unsplash%29.jpg)

## 色彩与颗粒

24 个 `.cube` 来自 YahiaAngelo/Film-Luts 固定版本 `af957b6`。随包按 Curtayn 胶卷键命名，TSV 的 `upstream` 列记录上游路径。保留 Yahia 的 MIT 版权说明，同时保留 G’MIC、Pat David 等人的 HaldCLUT 来源及适用的 CC BY-SA 4.0。其余 24 款标为自制近似。 [固定版本](https://github.com/YahiaAngelo/Film-Luts/tree/af957b631a304e6be94778b96cb3395de3438e9f) · [Film-Luts MIT](app/src/main/assets/licenses/film-luts-MIT.txt) · [G’MIC](https://gmic.eu/color_presets/negative_color_sample_1.html) · [Pat David](https://patdavid.net/topics/film-emulation-presets/) · [HaldCLUT](https://github.com/NatronGitHub/clut) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

颗粒使用 Curtayn 自己的 GLES 实现。分开控制强度与尺寸，主要作用在中间调，参考 darktable。输出短边参与颗粒尺寸计算，参考 Newson 等人的跨分辨率渲染思路。grainifier 的实拍扫描示例用于观察颗粒。 [darktable](https://github.com/darktable-org/darktable/blob/master/src/iop/grain.c) · [Newson 等人的实现](https://github.com/alasdairnewson/film_grain_rendering) · [grainifier](https://github.com/grainifier/grainifier)

## 机身、图标和声音

以原机顶板照片和说明书核对快门、拨杆、转轴与拨盘的位置。ImageGen 重绘主要部件，改动视角、光线、透明背景和构图。照片作者和对应许可列在随包署名表。启动图标采用 Pciarl 的 Nikon FM2 顶板参考，按 CC BY-SA 3.0 分发。胶皮、拉丝和漆面纹理采用独立生成的素材。 [部件与声音署名](app/src/main/assets/licenses/MECHANICS.md) · [FM2 顶板原照](https://commons.wikimedia.org/wiki/File:Fm2_top.jpg) · [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/)

声音来自 Freesound 的有许可相机录音，所用片段经过裁切、重采样、响度校准和淡入淡出。部分机型的快门以及拨盘卡点由代码合成。徕卡风格使用 M3 的公开录音作旁轴参考。 [录音原作者及许可](app/src/main/assets/licenses/MECHANICS.md#快门过片和感光度拨盘素材)

## 35mm 片边

- 富士的 `RDP III`、`RVP 100`、`RA`、`S-200`、`S-400`、`REALA`、`400PR` 与 `100 ACROS` 取自厂商产品资料。补充核对 Velvia 50、Provia 400X 和日本版 PN400N。 [富士资料总表](https://asset.fujifilm.com/www/us/files/2020-03/d52487c5c6f84e7f935c299491c5c1ff/ProfessionalFilmDataGuide.pdf) · [Velvia 50](https://asset.fujifilm.com/www/jp/files/2024-04/7d7888aab11c548b2acd50f0fee9e504/datasheet_velvia50_01.pdf) · [Provia 400X](https://www.fujifilm.com.hk/products/professional_films/pdf/provia_400x_datasheet.pdf) · [PN400N 包装](https://commons.wikimedia.org/wiki/File:FUJIFILM_PN400N_Pkg.JPG)
- 柯达 `400TX`、`TP` 与 `P3200TMZ` 核对 Kodak Professional Catalog L-9。彩色 `KR`、`KL`、`EB`、`GB` 查 F-507。历史版 `C`、`CX` 核对包装实拍。 [Kodak L-9](https://filmcolors.org/wp-content/uploads/2025/11/2003KodakProfessionalCatalog_L9.pdf) · [Kodak F-507](https://125px.com/docs/unsorted/kodak/f507ckic.pdf) · [Kodacolor II](https://commons.wikimedia.org/wiki/File:Kodacolor_II_Film.jpg) · [Kodacolor-X](https://commons.wikimedia.org/wiki/File:Kodacolor-X_35mm_Film_Cassette_%26_Box.jpg)

型号代码保存在 TSV 的 `product_code` 列。边缘位置按应用的模拟扫描布局绘制，帧号由应用的装卷和拍摄计数生成。 [胶卷目录](app/src/main/assets/films/catalog.tsv)
