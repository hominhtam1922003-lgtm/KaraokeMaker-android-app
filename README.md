# Karaoke Maker — bản Android

App điện thoại của tool Karaoke Maker: dán link YouTube hoặc chọn file nhạc
→ tách beat (nhanh hoặc AI) → AI chép lời & căn nhịp từng từ → chỉnh phong
cách chữ → xuất video karaoke MP4 để đăng YouTube/TikTok.

- Giao diện theo khung “KaraStudio”: **Thư viện → Studio → Xuất**
  (không có màn hát/chấm điểm — đây là tool làm video).
- Mọi xử lý chạy ngay trên điện thoại, ở nền (Foreground Service), trạng
  thái lưu bền — đóng app mở lại làm tiếp được.
- Model AI (chép lời Whisper small ~376 MB, tách beat MDX ~67 MB) tải lần
  đầu khi cần, không nằm trong APK.

Chi tiết kỹ thuật, quyết định thư viện và các điểm chờ kiểm chứng:
xem [BUILD-NOTES.md](BUILD-NOTES.md). Mẫu workflow CI:
[docs/WORKFLOW-build-android.yml](docs/WORKFLOW-build-android.yml).
