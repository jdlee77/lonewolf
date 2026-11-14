package com.atakmap.android.lonewolf.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;
import com.atakmap.android.lonewolf.LoneWolfDropDownReceiver;

/**
 * Toolbar / overflow-menu entry. Tapping it broadcasts the SHOW intent.
 */
public class LoneWolfTool extends AbstractPluginTool {

    public LoneWolfTool(Context context) {
        super(context,
                context.getString(R.string.app_name),
                context.getString(R.string.app_name),
                context.getResources().getDrawable(R.drawable.ic_lonewolf, context.getTheme()),
                LoneWolfDropDownReceiver.SHOW);
    }
}
