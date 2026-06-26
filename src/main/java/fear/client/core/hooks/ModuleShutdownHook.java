package fear.client.core.hooks;

import fear.client.core.manager.client.ModuleManager;

public class ModuleShutdownHook extends Thread {
    @Override
    public void run() {
        if (ModuleManager.unHook.isEnabled())
            ModuleManager.unHook.disable();
    }
}
