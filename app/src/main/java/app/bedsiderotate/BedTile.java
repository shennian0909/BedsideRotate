package app.bedsiderotate;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class BedTile extends TileService {
    private static final String TILE_LABEL = "懒人翻身";
    private static final int MAIN_ACTIVITY_REQUEST_CODE = 1;

    private boolean listening;

    @Override
    public void onStartListening() {
        super.onStartListening();
        listening = true;
        BedsideActivity.preferences(this).edit()
                .putBoolean(BedsideActivity.PREF_TILE_ADDED, true)
                .apply();
        updateTile(RotationService.running);
    }

    @Override
    public void onStopListening() {
        listening = false;
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();

        if (!BedsideActivity.isTileEnabled(this)) {
            openMainActivity();
            return;
        }

        if (!Settings.System.canWrite(this)) {
            openMainActivity();
            return;
        }

        if (RotationService.running) {
            Intent stopIntent = new Intent(this, RotationService.class)
                    .setAction(RotationService.STOP);
            startService(stopIntent);
            updateTile(false);
            return;
        }

        Intent startIntent = new Intent(this, RotationService.class)
                .setAction(RotationService.START);
        startForegroundService(startIntent);
        updateTile(true);
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        BedsideActivity.preferences(this).edit()
                .putBoolean(BedsideActivity.PREF_TILE_ADDED, true)
                .apply();
        updateTile(RotationService.running);
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
        BedsideActivity.preferences(this).edit()
                .putBoolean(BedsideActivity.PREF_TILE_ADDED, false)
                .apply();
    }

    private void updateTile(boolean running) {
        if (!listening) {
            return;
        }

        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        boolean enabled = BedsideActivity.isTileEnabled(this);
        tile.setLabel(TILE_LABEL);
        tile.setState(!enabled
                ? Tile.STATE_UNAVAILABLE
                : (running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE));
        tile.updateTile();
    }

    // Android 8–13 expose only the Intent overload for opening the permission screen.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @SuppressWarnings("deprecation")
    private void openMainActivity() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    MAIN_ACTIVITY_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
