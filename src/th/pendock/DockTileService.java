package th.pendock;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class DockTileService extends TileService {
    static void requestUpdate(Context context) {
        try {
            requestListeningState(context, new ComponentName(context, DockTileService.class));
        } catch (RuntimeException ignored) { }
    }

    @Override public void onStartListening() {
        super.onStartListening();
        updateTile(DockService.running);
    }

    @Override public void onClick() {
        super.onClick();
        if (DockService.running) {
            DockService.stop(this);
            updateTile(false);
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            if (DockService.start(this)) {
                updateTile(true);
                return;
            }
        }
        openApp();
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void updateTile(boolean active) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("PenDock");
        tile.setSubtitle(Appearance.text(this, active ? R.string.dock_running : R.string.dock_stopped));
        tile.updateTile();
    }
}
