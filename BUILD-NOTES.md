# Karaoke Maker Android — BUILD NOTES

App Android (bản điện thoại của Karaoke Maker PC), v1. Viết bởi Bạch Lộc
(Lập trình viên Trưởng) ngày 07/11/2026. Căn cứ: báo cáo khả thi
`~/workspace/karaoke-app/docs/android-feasibility.md`, tọa độ thư viện chuẩn
cuối `~/workspace/karaoke-android-deps.md` (Dương Mịch chốt), khung giao diện
“KaraStudio” do user gửi (4 ảnh, 07/11/2026).

## 1. Cây thư mục

```
karaoke-android-app/
├── settings.gradle / build.gradle / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   (xem mục 4 về wrapper jar)
├── docs/WORKFLOW-build-android.yml            (BẢN MẪU workflow — workflow thật
│                                               tạo ở .github/workflows/ qua web editor)
├── app/
│   ├── build.gradle                           (deps đã ghim, signing đọc từ env)
│   ├── proguard-rules.pro                     (minify TẮT ở v1)
│   ├── libs/                                  (đặt sherpa-onnx AAR vào đây — CI tự tải)
│   └── src/main/
│       ├── AndroidManifest.xml                (extractNativeLibs=true, FGS mediaProcessing)
│       ├── java/com/example/karaokemaker/
│       │   ├── KaraokeApp.kt                  (init store + yt-dlp nightly ở nền)
│       │   ├── core/
│       │   │   ├── Models.kt                  (TimedLine/StyleOptions/BgSpec/preset/mẫu nền)
│       │   │   ├── Lyrics.kt                  (PORT lyrics.py: parse LRC, lọc rác, build ASS, assign ends)
│       │   │   ├── Align.kt                   (PORT align.py + SequenceMatcher tự cài)
│       │   │   └── TranscribeLines.kt         (PORT segments_to_lines + _synth_words)
│       │   ├── data/
│       │   │   ├── ProjectStore.kt            (dự án + lines.json, thư mục riêng từng bài)
│       │   │   ├── JobStore.kt                (sổ job LƯU BỀN + quét gián đoạn)
│       │   │   └── TemplateStore.kt           (“Lưu cấu hình làm mẫu”)
│       │   ├── audio/
│       │   │   ├── AudioUtil.kt               (đọc/ghi WAV s16, peaks waveform)
│       │   │   ├── Ffmpeg.kt                  (FFmpegKit: trích nhạc, cắt stereo, nền, render)
│       │   │   ├── Mdx.kt                     (PORT mdx.py: STFT/FFT/model ONNX, theo khối RAM)
│       │   │   └── Whisper.kt                 (sherpa-onnx: chép lời, cắt đoạn theo lặng, DTW token)
│       │   ├── download/
│       │   │   ├── ModelManager.kt            (tải model lần đầu + đối chiếu size/sha256)
│       │   │   └── Youtube.kt                 (youtubedl-android: nightly, tải audio-only)
│       │   ├── service/
│       │   │   ├── JobService.kt              (Foreground Service: hàng đợi, thông báo, Huỷ)
│       │   │   └── Pipeline.kt                (các chặng: nạp/tách/chép/xuất — idempotent)
│       │   └── ui/
│       │       ├── MainActivity.kt            (THƯ VIỆN: thẻ dự án + chip, bài trong máy, model)
│       │       ├── AddSongActivity.kt         (Thêm bài: link YouTube / file SAF)
│       │       ├── StudioActivity.kt          (STUDIO: dòng lời, gắn nhịp, panel phong cách)
│       │       ├── ExportActivity.kt          (XUẤT: preset thẻ + hàng đợi xuất)
│       │       ├── KaraokePreviewView.kt      (xem trước tĩnh vẽ bằng Canvas)
│       │       └── WaveformView.kt            (sóng nhạc + mốc đoạn D1..Dn, chạm để tua)
│       └── res/ (layout/values/drawable theo palette KaraStudio)
```

## 2. Giao diện theo khung “KaraStudio” ở những điểm nào

- **Ngôn ngữ thị giác**: nền tối `#0B0B13`, thẻ `#15151F` bo góc 16dp + viền
  `#262638`, điểm nhấn gradient hồng `#FF4E8A` → tím `#A55BFF` (nút chính,
  nút phát tròn, thanh tiến độ), chip trạng thái có chấm màu: xanh
  `#34D399` (đã xuất/đã tách/đã đồng bộ), cam `#FFB020` (đang sửa/chưa đồng
  bộ), xám (chưa có/bản nháp).
- **Thư viện (ảnh 1)**: thẻ dự án có khối xem trước nhuộm gradient của bài +
  chip tỉ lệ, chip trạng thái tổng + chip **Beat** (Đã tách ✓/Chưa tách) +
  chip **Lời** (Đã đồng bộ/Đang sửa/Chưa có), nút “Mở studio”, mục **Bài hát
  trong máy** (quét MediaStore) để tạo dự án nhanh.
- **Studio (ảnh 2)**: khung **XEM TRƯỚC** (badge “XEM TRƯỚC · 16:9” + đồng
  hồ, chữ 2 dòng: đã hát tô màu highlight, dòng kế mờ, chấm đếm ngược, thanh
  tiến trình); danh sách **Dòng lời** (ô số thứ tự — dòng đang phát ô chuyển
  gradient, mốc giờ phải, nhãn cam “⚠ Chưa khớp”, ±0,1s, sửa dòng); **waveform**
  có chip mốc đoạn D1..Dn và vùng đã phát; nút **GẮN NHỊP** lớn = phím Space
  của khung (kèm công tắc “Chế độ gắn nhịp” + đếm “Đã gắn x/y câu”); panel
  **Phong cách** = panel phải của khung thu gọn thành tab (font, cỡ chữ,
  màu đã hát/chưa hát dạng ô màu, hiệu ứng, **song ca** với giọng từng dòng
  **Nữ/Nam** — mở rộng từ cơ chế xen kẽ của bản PC, nền & mẫu gradient,
  giọng dẫn, khung hình). Chip “✓ Đã lưu” tự hiện khi sửa (tự lưu ngay).
- **Xuất (ảnh 4)**: thẻ thông tin (thời lượng/nguồn nhạc/dòng lời/bộ mã hoá
  “✓ Phần cứng (MediaCodec)”/tên file/thư mục lưu), **thẻ preset** chọn được
  (YouTube 16:9 / Shorts-TikTok 9:16 / Facebook 1:1 — thẻ đang chọn viền hồng
  như khung; thẻ thứ 4 đổi thành **chất lượng 720p/1080p** thay cho 4K vì
  encode 4K trên điện thoại không ổn định), nút gradient “+ Thêm bài này vào
  hàng đợi xuất”, **Hàng đợi xuất** chạy lần lượt, mỗi bài có %/trạng thái/
  Mở xem/Chia sẻ/Chạy tiếp/Huỷ. Nút **Lưu cấu hình làm mẫu** ở đầu trang.
- **KHÔNG làm màn “Hát/chấm điểm” (ảnh 3)** theo lệnh user — đây là tool làm
  video để đăng, không phải app hát. Ảnh 3 chỉ dùng làm tham chiếu ngôn ngữ
  thị giác (nền tím + chữ lớn).

## 3. Quyết định kỹ thuật chính

- **Kotlin + Views/XML**, 1 module, package `com.example.karaokemaker`,
  minSdk 26, target/compileSdk 35, **chỉ arm64-v8a** (abiFilters), không
  coroutines (thread + Handler thuần cho bảo thủ), không Room (JSON file —
  ProjectStore/JobStore ghi atomic bằng file .tmp → rename).
- **Logic ASS/căn lời port trung thực** từ `lyrics.py`/`align.py` bản PC:
  cùng hằng số (lead, trọng số từ, hiệu ứng zoom/type, đếm ngược ≥1,6s,
  assign_ends...). Kotlin không có difflib nên `SequenceMatcher`
  (autojunk=False) được cài lại trong `Align.kt` theo cùng thuật toán khối
  khớp dài nhất. Mở rộng duy nhất có ghi chú trong code: giọng từng dòng
  (voice 0/1/2) theo khung KaraStudio — voice=0 giữ nguyên hành vi bản PC.
- **Chép lời = sherpa-onnx** (KHÔNG whisper.cpp theo chuẩn cuối): AAR
  `sherpa-onnx-static-link-onnxruntime-2.0.1.aar` đặt ở `app/libs/` (bản
  static để không đụng `libonnxruntime.so` với onnxruntime-android của MDX).
  Model small bản **có attention** (clairemcw, int8, ~376 MB) để DTW cho
  timestamp từng token; language="vi" cố định; cắt đoạn ≤28s tại khoảng lặng;
  chép trên stem giọng khi có. Thiếu timestamp token → tự lùi về giờ đoạn +
  nội suy (`TranscribeLines.synthWords`) như thang dự phòng của bản PC.
- **FFmpeg = `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9`** (ghim đúng;
  ≤8.1.8 hỏng drawtext). Bản full LGPL không có x264 → encode
  `h264_mediacodec` + `pix_fmt nv12` + bitrate theo cỡ khung (720p: 6000k,
  1080p: 10000k). Đốt ASS bằng filter `subtitles` (deps đã soi AAR: libass +
  filter hiện diện). Đường dẫn ASS là đường dẫn tuyệt đối trong filesDir —
  không chứa ký tự `:`/`,`/nháy nên an toàn cho cú pháp filter (khác ổ đĩa
  Windows ở bản PC).
- **YouTube = youtubedl-android `:library:0.18.1` CHỈ artifact này** (không
  `:ffmpeg` — app tải audio-only 1 luồng `bestaudio[ext=m4a]/bestaudio/best`,
  không gộp/không hậu xử lý nên yt-dlp không gọi ffmpeg; artifact đó còn lỗi
  align 16KB). **Tự cập nhật yt-dlp NIGHTLY**: khi mở app (nền) + trước mỗi
  lượt tải. Manifest có `extractNativeLibs="true"` (yêu cầu cứng).
- **Tách beat**: mặc định cắt stereo (L−R, nguyên lệnh bản PC). Tuỳ chọn AI
  MDX qua `onnxruntime-android:1.31.1` (intraOpThreads=4): thông số model giữ
  nguyên của `mdx.py` (n_fft 6144, hop 1024, DIM_F 3072, DIM_T 256, step 192,
  kênh “A”, zero 3 bin thấp, chuẩn hoá đỉnh 0.9, vocal = gốc − instrumental).
  **Độ lệch có chủ đích, đã ghi trong code**: bản PC giữ phổ cả bài trong RAM
  (>1 GB) → bản Android xử lý theo **khối 16s lõi + 3s chồng lấp**, trong
  khối thuật toán y hệt, nối chỉ lấy lõi. FFT 6144 (=2¹¹·3) cài bằng
  Cooley–Tukey mixed-radix tự viết (`Fft` trong Mdx.kt). App khai
  `largeHeap="true"` vì các mảng phổ theo khối vẫn ~100 MB đỉnh.
- **Model tải lần đầu, không nhét APK** (filesDir/models): MDX từ
  TRvlvr/model_repo — **bắt buộc đối chiếu sha256
  `317554b07fe1ea5279a77f2b1520a41ea4b93432560c4ffd08792c30fddf9adc`** +
  size 66.759.214 sau tải, sai là xoá + báo lỗi. Whisper: đối chiếu size
  từng file (112.445.386 / 262.482.583 / 816.730).
- **Mọi tác vụ dài trong Foreground Service** (loại `mediaProcessing`,
  thông báo tiến độ + nút Huỷ, WakeLock từng lượt). **JobStore lưu bền**:
  mở lại app thấy trạng thái; job “running” mồ côi → “interrupted” + nút
  **Chạy tiếp**; mọi chặng Pipeline idempotent theo file đầu ra (có file thì
  bỏ qua) nên chạy tiếp không làm lại từ đầu. Lỗi luôn hiện trong UI +
  thông báo, không chết im lặng.
- **Xuất**: mặc định 720p; lưu vào `Movies/KaraokeMaker` qua MediaStore
  (API 29+ không cần quyền; API 26–28 ghi thư mục công khai + đăng ký
  MediaStore) + nút chia sẻ. Tên file “<Tên bài> — Karaoke.mp4”.
- **Ký APK**: `app/build.gradle` đọc keystore từ biến môi trường
  `KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` (CI truyền từ
  secrets `KEYSTORE_B64` — base64 của file .jks — giải mã ra file tạm).
  Không có env → lùi về **debug signing** để vẫn build được (APK đó KHÔNG
  cài đè lên bản ký release — chỉ dùng kiểm tra build). Khoá ký phải cố định
  từ bản đầu như các app khác của user.
- **Proguard/minify: TẮT** (minifyEnabled false, shrinkResources false) cho
  v1 theo ưu tiên ổn định; proguard-rules.pro đã ghi sẵn các `-keep` cần nếu
  sau này bật R8.

## 4. Gradle wrapper

Repo có `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.9) nhưng
**thiếu `gradle-wrapper.jar` + script `gradlew`** (môi trường thi công không
chạy được Gradle để sinh). CI không phụ thuộc wrapper: workflow dùng
`gradle/actions/setup-gradle@v4` với `gradle-version: 8.9` rồi gọi lệnh
`gradle`. Trên máy dev có Gradle 8.9, chạy 1 lần `gradle wrapper` trong thư
mục này để sinh đủ bộ wrapper cho các lần sau.

## 5. Kiểm chứng đã làm trong môi trường này + điểm còn chờ máy thật

**ĐÃ KIỂM CHỨNG bằng biên dịch thật (kotlinc + JAR THẬT của đúng phiên bản
thư viện, tải về đối chiếu) — toàn bộ cây Kotlin biên dịch SẠCH:**
- `core/*` (Lyrics/Align/TranscribeLines/Models): biên dịch + **22 kiểm tra
  hành vi chạy thật đều PASS** (build ASS có \kf/Dots/duet/zoom/type,
  assignEnds, parseLrc+offset, lọc rác 2 chiều, alignFull ratio 1.0 + nội
  suy, segmentsToLines khử rác/gộp đuôi, synthWords chia đều).
- `audio/Mdx.kt` phần FFT: đối chiếu DFT trực tiếp ở n = 3/6/12/96/768/6144 —
  sai số ~1e-10, vòng thuận–ngược khôi phục tín hiệu ở mức 1e-15.
- `data/*` + `audio/AudioUtil.kt`: biên dịch sạch với android.jar.
- **API thư viện ngoài — biên dịch KHỚP với JAR thật**: sherpa-onnx 2.0.1
  (Whisper.kt), onnxruntime-android 1.31.1 (Mdx.kt), ffmpeg-kit-full 8.1.9
  (Ffmpeg.kt), youtubedl-android 0.18.1 (Youtube.kt — đã sửa 2 chỗ theo JAR
  thật: `YoutubeDLRequest` ở gói gốc không có `.library`, và
  `updateYoutubeDL` trả `UpdateStatus?` nullable).
- `service/*` + `ui/*` + `KaraokeApp`: biên dịch sạch với AndroidX thật
  (appcompat 1.7.0/core 1.15.0/recyclerview 1.3.2...) + R sinh từ res thật;
  đối chiếu chéo: 115 `R.id` dùng trong code đều có trong layout, mọi XML
  đều hợp lệ.
- **6 lỗi thật đã bắt & sửa trong vòng kiểm này** (đều là lỗi CI sẽ đỏ):
  Align index `Int?`; Lyrics chia nguyên thay vì chia thực ở phân bổ \k;
  import sai gói YoutubeDLRequest + status nullable; `val` cục bộ có getter
  (Kotlin cấm) trong StudioActivity → chuyển lên cấp class;
  `Color.DARK_GRAY` (Android chỉ có `DKGRAY`); `style` trong
  `Paint.apply{}` trỏ nhầm `Paint.style` ở KaraokePreviewView. Ngoài ra đã
  đổi mọi gán phức hợp trên phần tử mảng (`arr[i] /=`...) trong Mdx.kt sang
  dạng tường minh để tránh lệ thuộc hành vi trình biên dịch.

**CÒN CHỜ máy thật / CI (không kiểm được ở đây):**
1. **Định dạng token Whisper của sherpa**: biên dịch khớp nhưng việc gom
   token thành từ (token bắt đầu từ mới bằng khoảng trắng/Ġ/▁) và timestamp
   từng token của bản model attention chỉ biết đúng/sai khi chép thử 1 bài
   thật (đã có đường dự phòng nội suy giờ nếu timestamp rỗng).
2. **FGS type mediaProcessing trên máy Android < 15 / HyperOS**: cần
   smoke-test trên máy Android 14 và 15: nếu ROM nào chặn, phương án dự
   phòng là khai thêm type `dataSync` — tránh mặc định vì Android 15 giới
   hạn dataSync 6 giờ/ngày (deps đã cảnh báo).
3. **ffmpeg-kit 8.1.9 lúc chạy**: filter `gradients` (lavfi) + `subtitles` +
   overlay thanh tiến trình + `h264_mediacodec` trên máy Xiaomi thật; chuỗi
   filter `pan=...` cho cắt stereo giữ nguyên bản PC.
4. **Tốc độ/đỉnh RAM tách AI MDX**: biên dịch khớp ORT (`result[0].value` ép
   mảng 4 chiều chạy đúng kiểu); tốc độ thực tế + RAM với khối 16s cần đo
   trên máy user để chốt con số ETA trong hộp thoại cảnh báo (hiện tính
   2–4× thời lượng bài).
5. **Font cho libass**: mặc định “Roboto”; libass qua fontconfig của
   ffmpeg-kit có thể thay font dự phòng nếu không thấy đúng tên — kiểm tra
   bằng mắt trên video xuất thật.
6. **Waveform/peaks**: logic đọc WAV đã biên dịch + soi code, chưa chạy trên
   file nhạc thật trong môi trường này.

## 6. Cách build qua CI (tóm tắt)

1. Tạo repo `hominhtam1922003-lgtm/KaraokeMaker-android-app`, đẩy cây này lên.
2. Tạo workflow thật tại `.github/workflows/build-android.yml` bằng web
   editor, nội dung = `docs/WORKFLOW-build-android.yml` trong cây.
3. (Muốn ký release) Thêm secrets: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`,
   `KEY_ALIAS`, `KEY_PASSWORD`. Thiếu thì APK ký debug — chỉ để kiểm build.
4. Chạy workflow (push lên main hoặc dispatch tay; nhập `release_tag` kiểu
   `v1.0.0` để tạo GitHub Release kèm `KaraokeMaker.apk`).
5. Kiểm tra sau build như lệ thường: badging (package
   `com.example.karaokemaker`, versionName 1.0.0), chứng thư ký, cài đè thử.

## 7. Quy mô code (đếm thật)

58 file. Kotlin **5.695 dòng** (core 817 · data 512 · audio 1.052 ·
download 284 · service 695 · ui 2.306 · KaraokeApp 29). XML (layout +
values + manifest) **1.969 dòng**. Còn lại: Gradle cấu hình, docs, README.
