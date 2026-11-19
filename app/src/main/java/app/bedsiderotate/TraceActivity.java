package app.bedsiderotate;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class TraceActivity extends BedsideActivity {
    private static final int SAVE_TRACE = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button capture;
    private Button save;
    private final Runnable refresh = new Runnable() {
        public void run() {
            showState();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("录制姿态数据");
        title.setTextSize(26);
        column.addView(title);
        TextView guide = new TextView(this);
        guide.setText("先启动懒人翻身，点「开始录制」后可返回控制台或切到视频。每段最长 30 秒。画面回跳后保存 CSV，里面有转腕角、懒人翻身请求和实际显示方向。下一次录制会替换当前片段。");
        guide.setTextSize(16);
        guide.setPadding(0, pad, 0, pad);
        column.addView(guide);
        status = new TextView(this);
        status.setTextSize(17);
        column.addView(status);
        capture = new Button(this);
        capture.setOnClickListener(v -> {
            if (MotionTrace.recording) MotionTrace.finish();
            else if (RotationService.running) MotionTrace.begin();
            showState();
        });
        column.addView(capture);
        save = new Button(this);
        save.setText("保存 CSV");
        save.setOnClickListener(v -> {
            String filename = "懒人翻身-" + System.currentTimeMillis() + ".csv";
            if (Build.VERSION.SDK_INT >= 29) {
                saveToDownloads(filename, MotionTrace.csv());
                return;
            }
            Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv")
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_TITLE, filename);
            startActivityForResult(pick, SAVE_TRACE);
        });
        column.addView(save);
        Button back = new Button(this);
        back.setText("返回控制台");
        back.setOnClickListener(v -> finish());
        column.addView(back);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void showState() {
        int count = MotionTrace.samples;
        boolean active = MotionTrace.recording;
        capture.setText(active ? "结束本段录制" : "开始录制");
        capture.setEnabled(active || RotationService.running);
        save.setEnabled(!active && count > 0);
        status.setText(active ? "录制中 · 已收到 " + count + " 条传感器事件"
                : count > 0 ? "可保存 · " + count + " 条事件"
                : RotationService.running ? "等待录制" : "请先返回控制台启动懒人翻身");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_TRACE || result != RESULT_OK || data == null || data.getData() == null) return;
        if (MotionTrace.samples == 0) {
            toast("当前片段已释放，请重新录制。");
            return;
        }
        Uri destination = data.getData();
        String content = MotionTrace.csv();
        new Thread(() -> writeSelectedDocument(destination, content), "save-motion-trace").start();
    }

    @android.annotation.TargetApi(29)
    private void saveToDownloads(String filename, String content) {
        new Thread(() -> {
            ContentResolver resolver = getContentResolver();
            ContentValues file = new ContentValues();
            file.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            file.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            file.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BedsideRotate");
            file.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = null;
            try {
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, file);
                if (uri == null) throw new IOException("无法在下载目录创建文件");
                writeCsv(resolver, uri, content);
                file.clear();
                file.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, file, null, null);
                runOnUiThread(() -> toast("已保存到 Download/BedsideRotate/" + filename));
            } catch (IOException | RuntimeException e) {
                if (uri != null) resolver.delete(uri, null, null);
                reportSaveError(e);
            }
        }, "save-motion-trace").start();
    }

    private void writeSelectedDocument(Uri destination, String content) {
        ContentResolver resolver = getContentResolver();
        try {
            writeCsv(resolver, destination, content);
            runOnUiThread(() -> toast("姿态记录已保存"));
        } catch (IOException | RuntimeException e) {
            reportSaveError(e);
        }
    }

    private static void writeCsv(ContentResolver resolver, Uri destination, String content) throws IOException {
        try (OutputStream out = resolver.openOutputStream(destination)) {
            if (out == null) throw new IOException("文件无法打开");
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void reportSaveError(Exception error) {
        android.util.Log.w("MotionTrace", "Could not save CSV", error);
        runOnUiThread(() -> toast("保存失败：" + error.getLocalizedMessage()));
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
