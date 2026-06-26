package fear.client.features.modules.render;

import fear.client.features.modules.Module;
import fear.client.setting.Setting;

public class AspectRatio extends Module {
    public AspectRatio() {
        super("AspectRatio", Category.RENDER);
    }

    public Setting<Float> ratio = new Setting<>("Ratio", 1.78f, 0.1f, 5f);
}
