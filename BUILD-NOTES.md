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

## 8. v1.0.1 (versionCode 2) — bản vá theo verdict máy thật v1.0.0

**Bệnh gốc (verdict 07/11/2026):** user bấm "Tự làm trọn gói" trước khi
model MDX tải xong → hàng đợi tuần tự chạy tới chặng tách beat lúc model
chưa có → `ensureBeat` ném lỗi ở đúng mốc 18%; màn Thư viện không hiện lỗi
(`refreshJobs` chỉ hiện job active, thẻ dự án in nhãn tiến độ cũ "đã nạp
bài — 18%" + nút Huỷ) nên user tưởng job còn chạy, đợi oan ~1 giờ; chỉ vào
Studio mới thấy "bị lỗi" + Chạy tiếp. Kèm lỗi hiển thị trên Xiaomi: MỌI nút
bị hồng hoá + cắt chân chữ, nút "Quét nhạc" tàng hình.

**Đã sửa — 3 nhóm:**

1. **Pipeline tự bảo đảm model** (`service/Pipeline.kt`): thêm
   `downloadModelStage()` — `ensureBeat` mode "ai" khi `!mdxReady` thì TỰ
   TẢI MDX ngay trong chặng (tiến độ tải ánh xạ vào nửa đầu khoảng % của
   chặng, nhãn "đang tải model tách beat AI"); `ensureLyrics` tương tự với
   WHISPER_FILES (nhãn "đang tải model chép lời AI", ánh xạ [10%→35%] của
   chặng, các mốc sau tịnh tiến theo, % đơn điệu tăng). Lỗi tải ném NGUYÊN
   VĂN của `ModelManager` (mạng/size/sha256); vẫn tôn trọng Huỷ qua
   JobStore như các chặng khác. Model đã có sẵn -> hành vi y như cũ.
   Cập nhật 2 dòng chữ hướng dẫn trong `activity_add_song.xml` cho khớp
   hành vi mới (không còn bắt user tải model trước).
2. **Thư viện hiện lỗi thật** (`data/JobBanner.kt` MỚI + `ui/MainActivity.kt`
   + `activity_main.xml` + drawable mới `bg_card_error.xml`): logic chọn
   banner tách thuần thành `JobBanner.pick()` với 3 trạng thái
   Active/Failed/None — không có job active mà job kết thúc gần nhất là
   error/interrupted (và chưa có job done mới hơn nó) thì hiện THẺ LỖI đỏ:
   nhãn job + tên bài + nội dung lỗi ĐẦY ĐỦ + nút "Chạy tiếp"
   (`JobService.resume`); resume hoặc có job active mới là thẻ tự ẩn.
   Dòng tóm tắt trên thẻ dự án dùng `JobBanner.projectLineJob()` (job mới
   nhất của dự án): error -> "bị lỗi: <error>" đỏ, interrupted -> "bị gián
   đoạn" đỏ, done/huỷ -> ẩn (hết cảnh in nhãn tiến độ cũ của job đã chết).
   Nút HUỶ trên thẻ job (Thư viện + Studio + hàng đợi Xuất): chữ TRẮNG đậm.
3. **Layout cắt chữ — sửa tận gốc**: theme `Theme.MaterialComponents` +
   `colorPrimary` hồng khiến mọi `<Button>` phình thành MaterialButton:
   bị tint hồng đè lên background tự chế (nút "Quét nhạc" chữ hồng trên nền
   hồng = tàng hình; nút HUỶ đỏ trên hồng = nhoè) và inset mặc định của
   MaterialButton bóp khung chữ trong chiều cao cố định 36–52dp -> cắt
   chân chữ tiếng Việt. Sửa: đổi TOÀN BỘ 39 nút ở 7 layout sang
   `androidx.appcompat.widget.AppCompatButton` (tôn trọng background 100%,
   không tint, không inset lạ); chiều cao cố định -> `wrap_content` +
   `minHeight` (44–56dp tuỳ nút) + padding dọc tường minh 5–10dp để nút tự
   giãn theo cỡ chữ hệ thống. Hàng nút tua ở Studio hết tràn ngang:
   nút −5s/+5s cố định 56dp, đồng hồ `maxLines=1` + ellipsize, nút GẮN NHỊP
   giảm padding ngang 18->12dp + chữ 14sp.

**Kiểm chứng v1.0.1 (môi trường VM này, harness dựng lại tại
`~/workspace/karaoke-android-verify/` — harness gốc của v1.0.0 đã mất):**
- aapt2 compile + link toàn bộ res (gồm 7 layout đã sửa + drawable mới)
  với android.jar 34 + res của AAR thật: SẠCH; R.java có đủ 4 id mới
  (`cardJobError`, `tvJobErrorTitle`, `tvJobErrorText`, `btnJobResume`).
  (Bản sao scratch cho link phải đổi tạm parent theme sang AppCompat +
  `mediaProcessing`->`mediaPlayback` vì VM chỉ có SDK 34 — cây thật giữ
  nguyên, CI SDK 35 build như v1.0.0.)
- Biên dịch TOÀN CÂY Kotlin bằng kotlinc **2.0.21** (đúng bản CI) với
  JAR/AAR THẬT của đúng từng phiên bản (appcompat 1.7.0, core 1.15.0,
  recyclerview 1.3.2, material 1.12.0, ffmpeg-kit-full 8.1.9,
  youtubedl-android 0.18.1, onnxruntime-android 1.31.1, sherpa-onnx
  static 2.0.1 — file tải về đúng 38.577.050 byte như chuẩn): **0 lỗi**.
- Test hành vi core (viết lại đúng 22 ca đã kê ở mục 5): **22/22 PASS**.
- Test kịch bản lỗi cho phần mới (`JobBanner`): **15/15 PASS** — gồm:
  nhiều job lỗi chọn job mới nhất; lỗi cũ + job active mới -> hiện active;
  lỗi + job done mới hơn -> ẩn thẻ; resume (queued) -> thẻ lỗi biến mất,
  chuyển Active; projectLineJob đủ ca active/error/interrupted/done/
  canceled; và tái hiện đúng ca bệnh v1.0.0 (pipeline lỗi ở 18%).
- Chưa kiểm được ở đây (chờ CI + máy thật): giao diện nút sau khi đổi
  AppCompatButton trên HyperOS (hết hồng hoá/cắt chữ phải xác nhận bằng
  mắt trên máy user); pipeline tự tải model chạy trọn vòng trên máy thật.

## 9. v1.1.0 (vc3) — hàng loạt + Kim Vocal 2 + đồng hồ canh chặng (07/11/2026, Bạch Lộc)

Ba việc theo thiết kế Cổ Lực Na Trát + đo kiểm Dương Mịch. CHƯA push — chờ Tổng 98 đẩy từ
live chat kèm workflow release v1.1.0.

### 9.1 VIỆC 1 — Làm hàng loạt (batch)

- `data/JobStore.kt`: `Job` thêm `batchId: String?` (persist key `batchId`; sổ cũ thiếu key →
  null; ghi JSONObject.NULL khi không có) và `lastProgressAt: Long` (persist; sổ cũ thiếu key →
  lấy `updatedAt`). `enqueue(..., batchId)` thêm tham số; `JobStore.progress()` cập nhật
  `lastProgressAt` trong RAM ở MỌI lần gọi (trước điểm tiết lưu ghi đĩa 400ms) — watchdog đọc
  bản RAM cùng tiến trình nên không bị trễ tiết lưu.
- `data/BatchPlan.kt` (MỚI, thuần, test được): trần `MAX_ITEMS = 10`, ước `250MB/bài`;
  `parseLinks` (mỗi dòng 1 link, chỉ giữ dòng chứa "youtu", khử trùng giữ thứ tự);
  `countError` (0 → lỗi nhập; >10 → chặn toàn bộ mẻ kèm câu báo rõ — không cắt lén);
  `shortageBytes` + `fmtSize` (báo thiếu bao nhiêu); `summarize` gom đợt theo batchId, đợt mới
  nhất lên trước, bài trong đợt theo thứ tự tạo, kèm đếm done/error/active/other.
- `ui/AddSongActivity.kt`: thẻ "LÀM HÀNG LOẠT" — ô dán nhiều link + nút "Tạo đợt từ các link"
  + nút "Chọn nhiều file nhạc/video…" (`OpenMultipleDocuments`) + checkbox "Dọn file trung gian
  sau mỗi bài" (mặc định BẬT cho batch). Luồng batch KHÔNG fetchTitle ở UI: project tạo với
  tiêu đề tạm (`Pipeline.YOUTUBE_PLACEHOLDER_TITLE` = "Bài từ YouTube" / tên file), payload
  `{sepMode, autoRender, cleanup}` lấy tại thời điểm tạo mẻ, style/nền chung qua
  `TemplateStore.applyTo` như project thường. Pre-flight StatFs TRƯỚC khi tạo mẻ: thiếu →
  toast báo rõ số MB/GB còn thiếu, KHÔNG tạo mẻ (không đọc được số trống → không chặn).
  File-picker: file không đọc được tên/không copy được vẫn tạo project + job (job tự báo lỗi
  rõ ràng), các file còn lại trong mẻ chạy tiếp bình thường.
- `service/Pipeline.kt`: `ensureSongWav` — sau khi tải YouTube xong và thời lượng hợp lệ, nếu
  tiêu đề project vẫn là tiêu đề tạm thì thử `Youtube.fetchTitle` điền tiêu đề thật + nguồn
  "youtube" cho project (lỗi lấy tiêu đề → giữ tạm, không chặn bài). Cuối `runPipeline` (chỉ khi
  THÀNH CÔNG) nếu payload `cleanup=true` → `cleanupIntermediates`: xoá `hear16k.wav` + mọi file
  `source.*` trong thư mục dự án; GIỮ song.wav/inst.wav/vocal.wav/lines.json/out.mp4. Lỗi dọn
  nuốt lặng (không lật job thành lỗi). Job lỗi giữa mẻ: hành vi sẵn có — JobService bắt lỗi từng
  job, ghi error, bài kế trong hàng đợi vẫn chạy (kịch bản đã kiểm ở mức logic + đọc code).
- `ui/MainActivity.kt`: mục "Đợt hàng loạt" (header + container, ẩn khi không có đợt) dựng từ
  `BatchPlan.summarize(jobs, projectTitles)`: thẻ đợt = tiêu đề đợt (tiêu đề bài đầu) + dòng
  tổng "x/y xong · n lỗi · m đang chạy/chờ" + nút "Huỷ cả đợt" (hỏi xác nhận; huỷ mọi job
  active của đợt qua `JobService.cancel`; chỉ hiện khi đợt còn job active) + từng bài 1 dòng
  (tiêu đề, trạng thái: đang chạy/chờ kèm nhãn chặng + % · "✔ Đã xong" · lỗi đỏ kèm nội dung
  lỗi · "Đã bị ngắt — bấm Chạy tiếp trong Studio" · "Đã huỷ") + nút "Mở Studio" từng bài.
  Layout mới `item_batch.xml`, `item_batch_song.xml` (nút AppCompatButton minHeight 40dp —
  giữ chuẩn chống cắt chữ của v1.0.1).
- Hạ tầng hàng đợi GIỮ NGUYÊN (tuần tự sẵn có); `JobService.enqueue` thêm tham số batchId
  truyền xuống JobStore. Sửa đổi JobService ở bản này chỉ thuộc việc 3 (xem 9.3).

### 9.2 VIỆC 2 — Kim Vocal 2 (đã TỰ XÁC MINH đủ 3 điểm trước khi nối)

Bằng chứng xác minh (đo thật 07/11/2026 trên VM, model tải về thư mục tạm rồi xoá):

- **URL chính thức:** `https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/Kim_Vocal_2.onnx`
  (asset trong release `all_public_uvr_models` của repo TRvlvr/model_repo — cùng họ phát hành
  với model hiện tại UVR-MDX-NET-Inst_HQ_3).
- **Dung lượng thật: 66.759.214 byte** (bằng hệt Inst_HQ_3 — cùng kiến trúc MDX-Net).
- **SHA-256 thật: `ce74ef3b6a6024ce44211a07be9cf8bc6d87728cc852a68ab34eb8e58cde9c8b`.**
- **Hợp đồng tensor (đọc bằng onnxruntime trên file thật):** input `input` float32
  `[batch, 4, 3072, 256]` → output `output` float32 cùng shape — KHỚP NGUYÊN cách `Mdx.kt`
  đang chạy (4 kênh "A", DIM_F 3072, DIM_T 256). Chạy thử 1 lượt suy luận trên phổ tổng hợp:
  output đúng shape, mọi giá trị hữu hạn, |max| ≈ 2,83.
- **Hướng stem:** Kim Vocal 2 dự đoán THẲNG VOCAL (khác Inst_HQ_3 dự đoán instrumental).

Nối code:

- `download/ModelManager.kt`: `MDX_NAME = "Kim_Vocal_2.onnx"`; `ModelFile` thêm
  `predictsVocal: Boolean = false`; spec MDX mới (URL/size/sha256 ở trên, `predictsVocal = true`)
  kèm comment bằng chứng. File model cũ Inst_HQ_3 còn trên máy user không bị xoá (chỉ không
  còn được dùng) — user tự xoá nếu muốn nhẹ máy.
- `audio/Mdx.kt`: tách phép suy stem thành hàm THUẦN `Mdx.splitStem(orig, predicted,
  predictsVocal): Pair<FloatArray, FloatArray>` trả (inst, vocal): predictsVocal=true →
  vocal = predicted (giữ nguyên), inst = (orig − predicted) kẹp [-1,1]; false → inst =
  predicted, vocal = (orig − predicted) kẹp [-1,1]. Quy tắc: stem SUY RA luôn bị kẹp, stem DỰ
  ĐOÁN giữ nguyên — khớp đúng hành vi bản cũ (bản cũ không kẹp inst dự đoán, có kẹp vocal suy
  ra). `separate()` thêm tham số `modelPredictsVocal`; Pipeline.ensureBeat truyền
  `ModelManager.MDX.predictsVocal`. Đầu ra bên ngoài giữ nguyên: `inst.wav` + `vocal.wav`.
- Nhãn hiển thị: Thư viện "Tách beat AI (Kim Vocal 2 · ~67 MB)", radio trong màn Thêm bài
  "…(model Kim Vocal 2)", hộp thoại Studio "Cần tải model Kim Vocal 2 (~67 MB)…".

### 9.3 VIỆC 3 — Đồng hồ canh chặng (watchdog, hết treo im lặng)

Sự cố gốc: v2.2.0 PC từng treo ở 47,2% suốt 12+ phút không ai biết; bản Android cũng có thể
treo lặng ở bất kỳ chặng native nào (ffmpeg/sherpa/MDX). Thiết kế:

- `data/Watchdog.kt` (MỚI, thuần): `STALL_LIMIT_MS = 10 phút`; `isStalled(job, now)` — chỉ cắt
  khi status = "running" VÀ `lastProgressAt > 0` VÀ im lặng ≥ 10 phút (lastProgressAt = 0 =
  sổ rất cũ → không cắt oan); `stallError(job)` =
  "Chặng <label> đứng yên quá 10 phút — đã dừng job, bấm Chạy tiếp để thử lại" (label trống →
  "đang xử lý").
- `service/JobService.kt`: thay ExecutorService bằng vòng bơm Thread tường minh có đánh số
  THẾ HỆ (`pumpGen`) + tập `abandonedJobs`. Luồng canh riêng (daemon, nhịp 30s) chạy khi
  service sống: job đang chạy mà `Watchdog.isStalled` → (1) đánh dấu abandoned (mọi đường ghi
  trạng thái của worker bị vô hiệu hoá từ đây), (2) JobStore.update status "error" +
  `stallError`, (3) `notifyFinished` (Thư viện hiện thẻ lỗi đỏ + nút Chạy tiếp như mọi lỗi
  khác), (4) `Ffmpeg.cancelCurrent()` huỷ phiên ffmpeg đang chạy, (5) tăng thế hệ + interrupt
  luồng worker + khởi động vòng bơm MỚI chạy ngay bài kế trong hàng đợi (kể cả bài kế của cùng
  đợt batch). Worker cũ nếu kẹt trong native không interrupt được sẽ tự kết thúc khi native trả
  về và bị chặn ghi trạng thái nhờ abandoned + thế hệ. MỌI đường hoàn tất job (xong/lỗi/huỷ)
  đều kiểm abandoned trước khi ghi; `finally` chỉ xoá `currentJobId` khi còn khớp job của mình
  (không dẫm chân vòng bơm mới). `onDestroy`: tăng thế hệ + interrupt cả worker lẫn luồng canh.
- **Lựa chọn áp dụng (ghi rõ theo yêu cầu):** watchdog áp cho MỌI loại job, KỂ CẢ job tải
  model. Lý do: tải model vẫn báo tiến độ theo byte thật (~1MB/nhịp) qua cùng
  `JobStore.progress()` nên job khoẻ không bao giờ chạm ngưỡng 10 phút im lặng; đổi lại nếu
  luồng tải model treo thật (socket chết lặng không timeout) thì user cũng được cứu thay vì
  đợi oan — đúng bài học sự cố v1.0.0.

### 9.4 Kiểm chứng v1.1.0 (VM — không Gradle, harness như mục 8)

- aapt2 compile toàn bộ res MỚI (thêm layout item_batch/item_batch_song + thẻ batch trong
  activity_add_song + mục đợt trong activity_main): SẠCH. Link: bộ zip thư viện cũ + app.zip
  mới — vấp xung đột styleable `SearchView` giữa material và appcompat khi link TAY (Gradle
  xử lý theo package riêng nên CI không dính); xử lý kiểu harness như các bản trước: link
  không kèm material.zip + ytdl.zip (res app không tham chiếu 2 thư viện này — nút đã là
  AppCompatButton, app_name lấy từ app) và scratch themes thay 3 item chỉ-Material
  (colorPrimaryVariant/colorSecondary/colorOnPrimary) bằng attr AppCompat — CHỈ ở cây scratch
  `verify/build/res`, cây thật giữ nguyên. Link SẠCH, R.java đủ mọi id mới (etBatchLinks,
  btnBatchLinks, btnPickFiles, cbCleanup, llBatches, tvBatchHeader, tvBatchTitle,
  tvBatchSummary, btnBatchCancel, llBatchSongs, tvSongTitle, tvSongStatus, btnSongStudio).
- **kotlinc 2.0.21 biên dịch TOÀN CÂY app (25 file Kotlin) + R.java: 0 lỗi.**
- **Test JVM: CoreTest 22/22 PASS (giữ nguyên) · BannerTest 15/15 PASS (giữ nguyên, gồm ca
  bệnh v1.0.0) · BatchTest MỚI 35/35 PASS**, gồm:
  - parseLinks: lọc dòng rác/không phải YouTube, khử trùng giữ thứ tự, rỗng → 0 link;
  - trần mẻ: 10 link OK, 11 link → countError chặn kèm câu báo rõ; 0 link → lỗi;
  - pre-flight: đủ chỗ → thiếu 0; thiếu đúng 1 byte → báo 1; trống hoàn toàn → báo đủ
    2 × 250MB; fmtSize "250 MB"/"1.5 GB";
  - gom đợt: 2 đợt + job lẻ — job lẻ và batchId "" bị loại, đợt mới nhất lên trước, bài giữ
    thứ tự tạo, đếm done/error/active/other đúng (ca "link hỏng giữa mẻ": bài giữa error, bài
    sau vẫn done, đợt không sập);
  - watchdog: đúng mốc 10' → cắt; 9'59" → chưa; job done treo lâu → không cắt; lastProgressAt
    = 0 → không cắt oan; nhãn rỗng → câu lỗi dùng "đang xử lý", nguyên văn câu lỗi khớp UI;
  - stem Kim Vocal 2 (số học thuần): cả 2 chiều (Kim: vocal = predicted, inst = orig −
    predicted; Inst_HQ: ngược lại), inst + vocal khôi phục đúng bản gốc (sai số ≤ 1e-5), 200
    mẫu ngẫu nhiên có seed, ca kẹp biên (suy ra 1.8 → kẹp 1.0, stem dự đoán −0.9 giữ nguyên),
    im lặng → 2 stem 0.
- Suy luận ONNX thật của Kim_Vocal_2.onnx trên VM: xem bằng chứng ở 9.2.
- Giới hạn kiểm như các bản trước: hành vi service/hàng đợi/watchdog theo thời gian thật và
  pipeline trên máy thật chỉ xác minh được bằng đọc code đối chiếu + test logic thuần — chốt
  cuối vẫn là CI build + verdict máy thật của user.

### 9.5 File đã sửa ở v1.1.0

- `app/build.gradle` (versionCode 3, versionName "1.1.0")
- `app/src/main/java/com/example/karaokemaker/data/JobStore.kt` (batchId + lastProgressAt)
- `app/src/main/java/com/example/karaokemaker/data/BatchPlan.kt` (MỚI)
- `app/src/main/java/com/example/karaokemaker/data/Watchdog.kt` (MỚI)
- `app/src/main/java/com/example/karaokemaker/ui/AddSongActivity.kt` (chế độ hàng loạt)
- `app/src/main/java/com/example/karaokemaker/ui/MainActivity.kt` (mục Đợt hàng loạt + nhãn model)
- `app/src/main/java/com/example/karaokemaker/ui/StudioActivity.kt` (nhãn model trong hộp thoại)
- `app/src/main/java/com/example/karaokemaker/service/JobService.kt` (bơm thế hệ + watchdog + batchId)
- `app/src/main/java/com/example/karaokemaker/service/Pipeline.kt` (tiêu đề tạm → thật, dọn trung gian)
- `app/src/main/java/com/example/karaokemaker/audio/Mdx.kt` (splitStem thuần 2 chiều)
- `app/src/main/java/com/example/karaokemaker/download/ModelManager.kt` (spec Kim Vocal 2)
- `app/src/main/res/layout/activity_add_song.xml` (thẻ LÀM HÀNG LOẠT + nhãn model)
- `app/src/main/res/layout/activity_main.xml` (mục đợt + nhãn model)
- `app/src/main/res/layout/item_batch.xml` + `item_batch_song.xml` (MỚI)
- Test (ngoài cây app, ở `~/workspace/karaoke-android-verify/test/BatchTest.kt`): 35 ca mới.
