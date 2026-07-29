package com.interview.helper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 这个 App 其实是一个"套壳浏览器"：打开就直接加载我们的面试复盘网页，
 * 全屏显示，没有地址栏，用起来跟原生 App 一样。
 * 录音、转写、AI 总结、AI 复盘、上传文件、分享 全部由网页端完成。
 */
public class MainActivity extends Activity {

    // 打开 App 就加载我们的网页（钥匙请在 App 内「设置」页填一次，之后自动记住；不写死密钥，避免泄露）
    private static final String TARGET_URL = "https://7869008e292e4b66b09994191b175125.app.codebuddy.work/index.html";

    private static final int REQUEST_RECORD = 1;
    private static final int REQUEST_SELECT_FILE = 100;

    private WebView webView;
    // 文件选择器回调（上传录音 / 上传简历要用）
    private ValueCallback<Uri[]> mFilePathCallback;

    // 系统原生录音（绕开 Android WebView 不稳定的 getUserMedia 麦克风）
    private MediaRecorder mRecorder;
    private boolean mRecording = false;
    private File mRecFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);             // 网页需要 JS
        settings.setDomStorageEnabled(true);            // 本地存储记录
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 在自己的网页里跳转，不弹出外部浏览器
        webView.setWebViewClient(new WebViewClient());

        // 处理网页请求"录音"权限（面试录音要用）
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                final String origin = request.getOrigin().toString();
                runOnUiThread(() -> {
                    // 只放行我们自己网站的权限请求，更安全
                    if (origin.contains("codebuddy.work")) {
                        request.grant(request.getResources());
                    } else {
                        request.deny();
                    }
                });
            }

            // 让网页里的 <input type="file"> 能调起系统文件选择器（上传录音 / 简历）
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) mFilePathCallback.onReceiveValue(null);
                mFilePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, REQUEST_SELECT_FILE);
                } catch (Exception e) {
                    mFilePathCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        // 暴露给网页的桥：网页点录音时调用，用来申请安卓麦克风权限
        webView.addJavascriptInterface(new MicBridge(), "AndroidBridge");
        // 暴露给网页的桥：系统原生录音（开始 / 停止），录完把文件传回网页转写
        webView.addJavascriptInterface(new RecorderBridge(), "AndroidRecorder");
        // 暴露给网页的桥：绕过 WebView 网络限制，用 App 原生 HTTP 上传文件到 COS
        webView.addJavascriptInterface(new UploadBridge(), "AndroidUploader");

        // 向系统申请录音权限（安卓 6.0 以上需要运行时申请）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD);
            }
        }

        webView.loadUrl(TARGET_URL);
    }

    // 网页通过 AndroidBridge 调用的桥
    private class MicBridge {
        // requestMic：仅确保系统麦克风权限已授予（不在此自动开始录音，避免丢失点击手势导致授权失败）
        @JavascriptInterface
        public void requestMic() {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD);
            }
        }
        // openExternal：用系统浏览器/对应 App 打开外部链接（如进入线上面试、打开地图）
        @JavascriptInterface
        public void openExternal(String url) {
            if (url == null || url.isEmpty()) return;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "无法打开链接：" + url, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 网页通过 AndroidRecorder 调用的桥：系统原生录音（绕开 WebView getUserMedia 的不稳定）
    private class RecorderBridge {
        // 开始录音：先确认系统麦克风权限，再用 MediaRecorder 录到应用私有目录
        @JavascriptInterface
        public void startRecording() {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD);
                Toast.makeText(MainActivity.this, "请允许麦克风权限后再次点击开始录音",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                mRecFile = new File(getExternalFilesDir(null), "interview_record.m4a");
                if (mRecFile.exists()) mRecFile.delete();
                mRecorder = new MediaRecorder();
                mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mRecorder.setOutputFile(mRecFile.getAbsolutePath());
                mRecorder.prepare();
                mRecorder.start();
                mRecording = true;
                evalJs("onNativeRecState('recording')");
            } catch (Exception e) {
                mRecording = false;
                evalJs("onNativeError('录音启动失败：" + e.getMessage() + "')");
            }
        }

        // 停止录音：结束 MediaRecorder，然后把文件分段 base64 传回网页去转写
        @JavascriptInterface
        public void stopRecording() {
            if (!mRecording || mRecorder == null) {
                evalJs("onNativeError('当前没有正在录音')");
                return;
            }
            try {
                mRecorder.stop();
            } catch (Exception ignore) {
            }
            try {
                mRecorder.release();
            } catch (Exception ignore) {
            }
            mRecorder = null;
            mRecording = false;
            evalJs("onNativeRecState('stopped')");
            if (mRecFile != null && mRecFile.exists()) {
                transferFile(mRecFile, "interview.m4a", "audio/m4a");
            } else {
                evalJs("onNativeError('录音文件未生成')");
            }
        }
    }

    // 在 UI 线程执行网页 JS（evaluateJavascript 必须在主线程）
    private void evalJs(String js) {
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    // 把录音文件分块读成 base64，逐块传给网页 onNativeChunk，最后 onNativeDone
    private void transferFile(File file, String name, String mime) {
        new Thread(() -> {
            try {
                FileInputStream fis = new FileInputStream(file);
                byte[] buf = new byte[512 * 1024];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    byte[] chunk = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                    String b64 = Base64.encodeToString(chunk, Base64.NO_WRAP);
                    final String js = "onNativeChunk('" + b64 + "')";
                    runOnUiThread(() -> webView.evaluateJavascript(js, null));
                }
                fis.close();
                runOnUiThread(() -> webView.evaluateJavascript(
                        "onNativeDone('" + name + "','" + mime + "')", null));
            } catch (Exception e) {
                evalJs("onNativeError('传输录音失败：" + e.getMessage() + "')");
            }
        }).start();
    }

    // 文件选择结果回传给网页
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT_FILE) {
            if (mFilePathCallback == null) return;
            Uri[] results = (data == null || resultCode != Activity.RESULT_OK)
                    ? null
                    : WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                            String[] permissions,
                                            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "麦克风已授权，请再次点击开始录音", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "麦克风权限被拒绝，请在系统设置中允许后重试", Toast.LENGTH_LONG).show();
            }
        }
    }

    // 网页通过 AndroidUploader 调用的桥：用 App 原生 HttpURLConnection 上传 COS，绕开 WebView 网络限制
    private class UploadBridge {
        @JavascriptInterface
        public void upload(String bucket, String region, String secretId, String secretKey,
                           String key, String mime, String base64) {
            if (bucket == null || region == null || secretId == null || secretKey == null
                    || key == null || base64 == null) {
                evalJs("onUploadErr('上传参数不完整')");
                return;
            }
            new Thread(() -> {
                try {
                    byte[] data = Base64.decode(base64, Base64.DEFAULT);
                    String url = CosSigner.presignedPutUrl(bucket, region, secretId, secretKey, key);
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("PUT");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("Content-Type", mime != null && !mime.isEmpty() ? mime : "application/octet-stream");
                    conn.setFixedLengthStreamingMode(data.length);
                    OutputStream out = conn.getOutputStream();
                    out.write(data);
                    out.flush();
                    out.close();
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        evalJs("onUploadOk('" + escapeJs(url) + "')");
                    } else {
                        String msg = readStream(conn.getErrorStream());
                        evalJs("onUploadErr('HTTP " + code + " " + escapeJs(msg) + "')");
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    evalJs("onUploadErr('" + escapeJs(e.getMessage() != null ? e.getMessage() : "上传异常") + "')");
                }
            }).start();
        }
    }

    // COS 预签名 URL 生成（签名 v1，仅用于 PUT Object）
    // 注：COS 的 sign_key 是 hex 字符串，第二次 HMAC 时直接把它当字符串用（不解码成字节）
    private static class CosSigner {
        static String presignedPutUrl(String bucket, String region, String secretId,
                                      String secretKey, String key) throws Exception {
            String host = bucket + ".cos." + region + ".myqcloud.com";
            String encodedKey = encodeKey(key);
            long now = System.currentTimeMillis() / 1000;
            long start = now - 60;       // COS SDK 习惯提前 60 秒开始
            long end = start + 3660;     // 有效 1 小时（与 COS SDK 行为对齐）
            String keyTime = start + ";" + end;
            String signKey = hmacSha1Hex(secretKey.getBytes(StandardCharsets.UTF_8), keyTime);
            String httpString = "put\n/" + encodedKey + "\n\nhost=" + encodeHeaderValue(host) + "\n";
            String stringToSign = "sha1\n" + keyTime + "\n" + sha1Hex(httpString) + "\n";
            String signature = hmacSha1Hex(signKey.getBytes(StandardCharsets.UTF_8), stringToSign);
            return "https://" + host + "/" + encodedKey
                    + "?q-sign-algorithm=sha1"
                    + "&q-ak=" + URLEncoder.encode(secretId, "UTF-8")
                    + "&q-sign-time=" + keyTime
                    + "&q-key-time=" + keyTime
                    + "&q-header-list=host"
                    + "&q-url-param-list="
                    + "&q-signature=" + signature;
        }

        static String encodeKey(String key) throws Exception {
            // 对 key 中特殊字符编码，斜杠保留作为路径分隔
            StringBuilder sb = new StringBuilder();
            for (char c : key.toCharArray()) {
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '/' || c == '-' || c == '_' || c == '.') {
                    sb.append(c);
                } else {
                    sb.append(URLEncoder.encode(String.valueOf(c), "UTF-8").replace("+", "%20"));
                }
            }
            return sb.toString();
        }

        static String encodeHeaderValue(String value) throws Exception {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        }

        static String hmacSha1Hex(byte[] key, String msg) throws Exception {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
            return bytesToHex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        }

        static String sha1Hex(String data) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return bytesToHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        }

        static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
    }

    private String readStream(java.io.InputStream is) {
        if (is == null) return "";
        try {
            byte[] buf = new byte[1024];
            int n;
            StringBuilder sb = new StringBuilder();
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            is.close();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // App 退出时释放录音资源，避免占用麦克风
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRecording && mRecorder != null) {
            try { mRecorder.stop(); } catch (Exception ignore) {}
            try { mRecorder.release(); } catch (Exception ignore) {}
            mRecorder = null;
            mRecording = false;
        }
    }

    // 按返回键时，先退回网页上一页，而不是直接退出 App
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
