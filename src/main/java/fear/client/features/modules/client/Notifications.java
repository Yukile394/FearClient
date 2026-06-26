package fear.client.features.modules.client;

import fear.client.features.modules.Module;
import fear.client.setting.Setting;

public final class Notifications extends Module {
    public Notifications() {
        super("Notifications", Category.CLIENT);
    }

    public final Setting<Mode> mode = new Setting<>("Mode", Mode.CrossHair);

    public enum Mode {
        Default, CrossHair, Text
    }
}
