package app.bedsiderotate;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class AboutActivity extends BedsideActivity {
    @Override protected void onCreate(Bundle state) {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light);
        super.onCreate(state);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        getActionBar().setTitle(R.string.about_title);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        setContentView(scroll);

        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextSize(26);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(name);

        Button project = new Button(this);
        project.setText(R.string.github_project);
        project.setAllCaps(false);
        project.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        project.setCompoundDrawablePadding(dp(12));
        Drawable mark = getDrawable(R.drawable.ic_github).mutate();
        mark.setTint(project.getCurrentTextColor());
        project.setCompoundDrawablesRelativeWithIntrinsicBounds(mark, null, null, null);
        project.setOnClickListener(v -> {
            Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.project_url)));
            try {
                startActivity(open);
            } catch (ActivityNotFoundException error) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams projectLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        projectLayout.topMargin = padding;
        content.addView(project, projectLayout);

        TextView license = new TextView(this);
        license.setText(R.string.project_license);
        license.setTextSize(16);
        license.setPadding(0, padding, 0, dp(8));
        content.addView(license);
        Button fullText = new Button(this);
        fullText.setText(R.string.read_license);
        fullText.setOnClickListener(v -> showLicense());
        content.addView(fullText);
    }

    private void showLicense() {
        StringBuilder text = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(getAssets().open("gpl-3.0.txt"), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                text.append(buffer, 0, count);
            }
        } catch (IOException error) {
            Toast.makeText(this, R.string.license_read_error, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("GNU GPL v3.0").setMessage(text)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
