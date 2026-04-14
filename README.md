# TV Phone Internet Call

Aplikasi panggilan audio untuk Android TV dan Android HP yang memakai internet melalui server WebSocket.

Fitur saat ini:
- satu APK bisa dipasang di Android TV maupun Android phone
- perangkat register ke server yang sama lalu saling melihat daftar online
- setiap perangkat punya kode 6 digit untuk dipanggil
- incoming call, answer, reject, end call
- audio call lewat relay server berbasis WebSocket
- keypad tetap ramah remote TV dan tetap nyaman dipakai touch di HP

Cara pakai:
1. Jalankan signaling server dari folder `signaling-server`.
2. Install aplikasi yang sama di TV dan HP.
3. Buka aplikasi di kedua perangkat dan izinkan mikrofon.
4. Isi alamat server, misalnya `ws://IP-SERVER:8080`, lalu tekan `Connect`.
5. Lihat kode perangkat masing-masing.
6. Masukkan kode tujuan lalu tekan `Call`.

Menjalankan server:

```bash
cd signaling-server
npm install
npm start
```

Deploy ke Railway:
1. Push repo ini ke GitHub.
2. Di Railway, buat service baru dari repo tersebut.
3. Set `Root Directory` service ke `signaling-server`.
4. Railway akan membaca [signaling-server/railway.toml](/home/mocharfian/Documents/projects/tv/chatapp/signaling-server/railway.toml:1).
5. Setelah deploy berhasil, ambil domain Railway dan isi di aplikasi sebagai `wss://nama-service.up.railway.app`.

Catatan deploy:
- Server sekarang menyediakan endpoint HTTP `GET /health` untuk healthcheck Railway.
- File [signaling-server/Dockerfile](/home/mocharfian/Documents/projects/tv/chatapp/signaling-server/Dockerfile:1) juga disiapkan kalau kamu ingin build via Dockerfile.
- Untuk monorepo seperti repo ini, Railway menyarankan memakai `Root Directory` untuk service backend terisolasi.

Catatan penting:
- Versi ini membutuhkan kedua aplikasi aktif dan sama-sama terhubung ke server.
- Ini sudah berjalan lewat internet, tetapi belum memakai push notification saat app ditutup.
- Untuk produksi yang lebih matang, langkah lanjutnya adalah autentikasi user, notifikasi `FCM`, dan media stack `WebRTC`.
- Railway cocok untuk signaling server ini, tetapi TURN server WebRTC penuh, terutama relay UDP publik, sebaiknya ditempatkan di layanan atau VPS terpisah.

Build lokal:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
```

Server ada di:
- `signaling-server/server.js`
