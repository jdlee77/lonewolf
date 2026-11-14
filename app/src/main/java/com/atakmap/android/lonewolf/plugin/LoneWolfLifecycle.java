package com.atakmap.android.lonewolf.plugin;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.lonewolf.LoneWolfMapComponent;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;

/**
 * Plugin entry point, referenced from assets/plugin.xml.
 */
public class LoneWolfLifecycle extends AbstractPlugin implements IPlugin {

    public LoneWolfLifecycle(IServiceController serviceController) {
        super(serviceController,
                new LoneWolfTool(serviceController.getService(PluginContextProvider.class).getPluginContext()),
                new LoneWolfMapComponent());
    }
}
