package com.atakmap.android.lonewolf;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.lonewolf.core.LoneWolfController;
import com.atakmap.android.lonewolf.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;

/**
 * Created by ATAK when the plugin loads. Owns the controller (which keeps
 * monitoring whether or not the pane is open) and registers the drop-down.
 */
public class LoneWolfMapComponent extends DropDownMapComponent {

    private LoneWolfController controller;
    private LoneWolfDropDownReceiver dropDown;

    @Override
    public void onCreate(final Context context, Intent intent, final MapView view) {
        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);

        controller = new LoneWolfController(view, context);
        dropDown = new LoneWolfDropDownReceiver(view, context, controller);

        DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(LoneWolfDropDownReceiver.SHOW, "Show the Lone Worker pane");
        registerDropDownReceiver(dropDown, filter);

        controller.start(FileSystemUtils.getItem("tools/lonewolf/logs"));
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        if (controller != null)
            controller.dispose();
        super.onDestroyImpl(context, view);
    }
}
