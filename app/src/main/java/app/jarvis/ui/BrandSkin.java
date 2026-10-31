package app.jarvis.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.IOException;
import java.io.InputStream;

/** Generated brand picture for the assistant orb; null when the asset is missing, and the orb then draws itself. */
public final class BrandSkin {
    private static final String ORB_ASSET = "brand/orb.webp";
    private static Bitmap orb;
    private static boolean loaded;

    private BrandSkin() { }

    public static synchronized Bitmap orb(Context c) {
        if (!loaded) {
            loaded = true;
            try (InputStream in = c.getAssets().open(ORB_ASSET)) {
                orb = BitmapFactory.decodeStream(in);
            } catch (IOException missing) {
                orb = null;
            }
        }
        return orb;
    }
}
