package app.bedsiderotate;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class AboutActivity extends StyledActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = createPage(scroll);
        Button back = createButton(getString(R.string.about_back), ButtonStyle.GHOST);
        back.setMinHeight(dp(48));
        back.setOnClickListener(v -> finish());
        String version = getString(R.string.about_version, BuildConfig.VERSION_NAME);
        page.addView(createBrandHeader(getString(R.string.about_title), version, back), matchWrap());
        page.addView(createRule());

        Uri repository = Uri.parse(getString(R.string.project_url));
        Button project = createButton(getString(R.string.github_project), ButtonStyle.SECONDARY);
        Drawable mark = getDrawable(R.drawable.ic_github).mutate();
        mark.setTint(project.getCurrentTextColor());
        project.setCompoundDrawablesRelativeWithIntrinsicBounds(mark, null, null, null);
        project.setCompoundDrawablePadding(dp(8));
        project.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, repository));
            } catch (ActivityNotFoundException error) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
            }
        });
        Button fullText = createButton(getString(R.string.read_license), ButtonStyle.SECONDARY);
        fullText.setOnClickListener(v -> showLicense());

        LinearLayout source = infoCard(getString(R.string.source_code),
                String.join("/", repository.getPathSegments()), project);
        LinearLayout license = infoCard(getString(R.string.license_heading),
                getString(R.string.project_license), fullText);
        boolean tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(tablet ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sourceParams = tablet
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : matchWrap();
        LinearLayout.LayoutParams licenseParams = new LinearLayout.LayoutParams(sourceParams);
        if (tablet) {
            sourceParams.setMarginEnd(dp(16));
        } else {
            sourceParams.bottomMargin = dp(16);
        }
        cards.addView(source, sourceParams);
        cards.addView(license, licenseParams);
        page.addView(cards, matchWrap());
        setContentView(scroll);
    }

    private LinearLayout infoCard(String title, String description, Button action) {
        LinearLayout card = createCard();
        card.addView(sectionTitle(title), matchWrap());
        TextView detail = text(description, 14f, palette.muted, Typeface.NORMAL);
        detail.setPadding(0, dp(8), 0, dp(16));
        card.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        action.setMinHeight(dp(48));
        card.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void showLicense() {
        StringBuilder license = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(getAssets().open("gpl-3.0.txt"), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                license.append(buffer, 0, count);
            }
        } catch (IOException error) {
            Toast.makeText(this, R.string.license_read_error, Toast.LENGTH_SHORT).show();
            return;
        }
        TextView heading = sectionTitle("GNU GPL v3.0");
        heading.setPadding(dp(24), dp(20), dp(24), dp(8));
        int theme = nightMode ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(this, theme)
                .setCustomTitle(heading).setMessage(license)
                .setPositiveButton(android.R.string.ok, null).show();
        dialog.getWindow().setBackgroundDrawable(rounded(palette.surface, dp(18), dp(1), palette.line));
        TextView message = dialog.findViewById(android.R.id.message);
        message.setTextColor(palette.ink);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(palette.accentText);
    }
}
