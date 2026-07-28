package com.interview.helper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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
